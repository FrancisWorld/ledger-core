package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.in.HoldUseCase;
import io.github.francisworld.ledger.application.port.out.AccountRepository;
import io.github.francisworld.ledger.application.port.out.HoldRepository;
import io.github.francisworld.ledger.application.port.out.IdGenerator;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.OutboxStore.NewEvent;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.Direction;
import io.github.francisworld.ledger.domain.EntryDraft;
import io.github.francisworld.ledger.domain.Hold;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Posting;
import io.github.francisworld.ledger.domain.Transaction;
import io.github.francisworld.ledger.domain.TransactionKind;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hold lifecycle. Lock order is always hold first, then accounts in ascending id order; no other
 * code path locks a hold after an account, so the combined ordering stays acyclic.
 */
public final class HoldService implements HoldUseCase {

  private static final int EXPIRY_BATCH = 100;

  private final HoldRepository holds;
  private final AccountRepository accounts;
  private final OutboxStore outbox;
  private final JournalWriter writer;
  private final UnitOfWork unitOfWork;
  private final IdGenerator ids;
  private final Clock clock;
  private final LedgerMetrics metrics;

  HoldService(
      HoldRepository holds,
      AccountRepository accounts,
      OutboxStore outbox,
      JournalWriter writer,
      UnitOfWork unitOfWork,
      IdGenerator ids,
      Clock clock,
      LedgerMetrics metrics) {
    this.holds = holds;
    this.accounts = accounts;
    this.outbox = outbox;
    this.writer = writer;
    this.unitOfWork = unitOfWork;
    this.ids = ids;
    this.clock = clock;
    this.metrics = metrics;
  }

  @Override
  public Hold place(PlaceHold command) {
    Instant now = clock.instant();
    Hold hold =
        Hold.place(
            ids.next(),
            command.accountId(),
            command.amount(),
            command.currency(),
            command.reference(),
            command.expiresAt(),
            now);
    return unitOfWork.execute(
        () -> {
          Account account = writer.lock(List.of(command.accountId())).get(command.accountId());
          if (!account.currency().equals(command.currency())) {
            throw LedgerException.unprocessable(
                "currency-mismatch",
                "Account %s is denominated in %s, not %s"
                    .formatted(account.code(), account.currency(), command.currency()));
          }
          accounts.updateBalances(List.of(account.reserve(command.amount())));
          holds.insert(hold);
          outbox.append(event("hold.created", hold));
          return hold;
        });
  }

  @Override
  public Hold get(UUID holdId) {
    return holds.find(holdId).orElseThrow(() -> notFound(holdId));
  }

  @Override
  public Captured capture(CaptureHold command) {
    return unitOfWork.execute(
        () -> {
          Instant now = clock.instant();
          Hold hold = holds.lock(command.holdId()).orElseThrow(() -> notFound(command.holdId()));
          long amount = command.amount() == null ? hold.amount() : command.amount();
          Hold captured = hold.capture(amount, now);
          if (hold.accountId().equals(command.destinationAccountId())) {
            throw LedgerException.invalid(
                "same-account", "Destination must differ from the held account");
          }
          List<EntryDraft> drafts =
              List.of(
                  new EntryDraft(hold.accountId(), Direction.DEBIT, amount),
                  new EntryDraft(command.destinationAccountId(), Direction.CREDIT, amount));
          Map<UUID, Account> locked = writer.lock(Posting.lockOrder(drafts));
          Account destination = locked.get(command.destinationAccountId());
          if (!destination.currency().equals(hold.currency())) {
            throw LedgerException.unprocessable(
                "currency-mismatch",
                "Destination is denominated in %s, hold in %s"
                    .formatted(destination.currency(), hold.currency()));
          }
          // The whole reservation is dissolved: the captured part becomes a debit, the rest is free
          // again.
          locked.computeIfPresent(
              hold.accountId(), (id, source) -> source.unreserve(hold.amount()));
          Transaction transaction =
              writer.write(
                  new JournalWriter.Header(
                      TransactionKind.CAPTURE, command.reference(), null, null, hold.id()),
                  drafts,
                  locked);
          Hold settled = captured.settledBy(transaction.id());
          holds.update(settled);
          outbox.append(event("hold.captured", settled));
          return new Captured(settled, transaction);
        });
  }

  @Override
  public Hold release(UUID holdId) {
    return unitOfWork.execute(
        () -> {
          Hold hold = holds.lock(holdId).orElseThrow(() -> notFound(holdId));
          Hold released = hold.release(clock.instant());
          unreserve(released);
          holds.update(released);
          outbox.append(event("hold.released", released));
          return released;
        });
  }

  @Override
  public int expireDue() {
    int expired =
        unitOfWork.execute(
            () -> {
              Instant now = clock.instant();
              List<Hold> due = holds.lockExpired(now, EXPIRY_BATCH);
              if (due.isEmpty()) {
                return 0;
              }
              // Same global order as every other writer: distinct accounts, ascending id.
              List<UUID> accountIds =
                  due.stream().map(Hold::accountId).distinct().sorted().toList();
              Map<UUID, Account> locked = writer.lock(accountIds);
              for (Hold hold : due) {
                Hold done = hold.expire(now);
                locked.computeIfPresent(hold.accountId(), (id, a) -> a.unreserve(hold.amount()));
                holds.update(done);
                outbox.append(event("hold.expired", done));
              }
              accounts.updateBalances(locked.values());
              return due.size();
            });
    if (expired > 0) {
      metrics.holdExpired(expired);
    }
    return expired;
  }

  private void unreserve(Hold hold) {
    Account account = writer.lock(List.of(hold.accountId())).get(hold.accountId());
    accounts.updateBalances(List.of(account.unreserve(hold.amount())));
  }

  private static NewEvent event(String type, Hold hold) {
    Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("hold_id", hold.id().toString());
    payload.put("account_id", hold.accountId().toString());
    payload.put("amount", hold.amount());
    payload.put("currency", hold.currency());
    payload.put("status", hold.status().name());
    payload.put("captured_amount", hold.capturedAmount());
    return new NewEvent("hold", hold.id().toString(), type, payload);
  }

  private static LedgerException notFound(UUID id) {
    return LedgerException.notFound("hold-not-found", "Hold " + id + " does not exist");
  }
}
