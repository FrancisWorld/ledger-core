package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.in.PostingUseCase;
import io.github.francisworld.ledger.application.port.out.TransactionRepository;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.Direction;
import io.github.francisworld.ledger.domain.EntryDraft;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Posting;
import io.github.francisworld.ledger.domain.Transaction;
import io.github.francisworld.ledger.domain.TransactionKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PostingService implements PostingUseCase {

  private final JournalWriter writer;
  private final TransactionRepository transactions;
  private final UnitOfWork unitOfWork;

  PostingService(JournalWriter writer, TransactionRepository transactions, UnitOfWork unitOfWork) {
    this.writer = writer;
    this.transactions = transactions;
    this.unitOfWork = unitOfWork;
  }

  @Override
  public Transaction transfer(Transfer command) {
    if (command.sourceAccountId().equals(command.destinationAccountId())) {
      throw LedgerException.invalid("same-account", "Source and destination must differ");
    }
    List<EntryDraft> drafts =
        List.of(
            new EntryDraft(command.sourceAccountId(), Direction.DEBIT, command.amount()),
            new EntryDraft(command.destinationAccountId(), Direction.CREDIT, command.amount()));
    return unitOfWork.execute(
        () -> {
          Map<UUID, Account> locked = writer.lock(Posting.lockOrder(drafts));
          for (Account account : locked.values()) {
            if (!account.currency().equals(command.currency())) {
              throw LedgerException.unprocessable(
                  "currency-mismatch",
                  "Account %s is denominated in %s, not %s"
                      .formatted(account.code(), account.currency(), command.currency()));
            }
          }
          return writer.write(
              new JournalWriter.Header(
                  TransactionKind.TRANSFER, command.reference(), command.description(), null, null),
              drafts,
              locked);
        });
  }

  @Override
  public Transaction post(Journal command) {
    List<EntryDraft> drafts =
        command.lines().stream()
            .map(l -> new EntryDraft(l.accountId(), l.direction(), l.amount()))
            .toList();
    return unitOfWork.execute(
        () ->
            writer.write(
                new JournalWriter.Header(
                    TransactionKind.JOURNAL,
                    command.reference(),
                    command.description(),
                    null,
                    null),
                drafts));
  }

  @Override
  public Transaction reverse(UUID transactionId, String reason) {
    return unitOfWork.execute(
        () -> {
          Transaction original =
              transactions.lock(transactionId).orElseThrow(() -> notFound(transactionId));
          if (original.kind() == TransactionKind.REVERSAL) {
            throw LedgerException.conflict(
                "cannot-reverse-reversal", "A reversal cannot itself be reversed");
          }
          if (transactions.isReversed(transactionId)) {
            throw LedgerException.conflict(
                "already-reversed", "Transaction " + transactionId + " was already reversed");
          }
          return writer.write(
              new JournalWriter.Header(
                  TransactionKind.REVERSAL,
                  "reversal:" + transactionId,
                  reason,
                  transactionId,
                  original.holdId()),
              Posting.inverse(original.entries()));
        });
  }

  @Override
  public Transaction get(UUID transactionId) {
    return transactions.find(transactionId).orElseThrow(() -> notFound(transactionId));
  }

  private static LedgerException notFound(UUID transactionId) {
    return LedgerException.notFound(
        "transaction-not-found", "Transaction " + transactionId + " does not exist");
  }
}
