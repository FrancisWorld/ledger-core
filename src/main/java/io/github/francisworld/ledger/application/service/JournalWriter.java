package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.out.AccountRepository;
import io.github.francisworld.ledger.application.port.out.IdGenerator;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.OutboxStore.NewEvent;
import io.github.francisworld.ledger.application.port.out.TransactionRepository;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.Entry;
import io.github.francisworld.ledger.domain.EntryDraft;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Posting;
import io.github.francisworld.ledger.domain.Transaction;
import io.github.francisworld.ledger.domain.TransactionKind;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared write path for everything that moves money. It must run inside a unit of work: accounts
 * are locked one by one in ascending id order (so two transactions can never wait on each other in
 * a cycle), the posting is computed in memory, then the transaction, the new balances and the
 * outbox event are written together.
 */
final class JournalWriter {

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final OutboxStore outbox;
  private final IdGenerator ids;
  private final Clock clock;
  private final LedgerMetrics metrics;

  JournalWriter(
      AccountRepository accounts,
      TransactionRepository transactions,
      OutboxStore outbox,
      IdGenerator ids,
      Clock clock,
      LedgerMetrics metrics) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.outbox = outbox;
    this.ids = ids;
    this.clock = clock;
    this.metrics = metrics;
  }

  /** What to record about a transaction besides its entries. */
  record Header(
      TransactionKind kind, String reference, String description, UUID reversesId, UUID holdId) {}

  /** Locks the accounts in ascending id order and returns them keyed by id. */
  Map<UUID, Account> lock(List<UUID> sortedIds) {
    Map<UUID, Account> locked = new LinkedHashMap<>();
    for (UUID id : sortedIds) {
      locked.put(
          id,
          accounts
              .lock(id)
              .orElseThrow(
                  () ->
                      LedgerException.notFound(
                          "account-not-found", "Account " + id + " does not exist")));
    }
    return locked;
  }

  Transaction write(Header header, List<EntryDraft> drafts) {
    return write(header, drafts, lock(Posting.lockOrder(drafts)));
  }

  /**
   * Posts against accounts the caller has already locked (and possibly adjusted, e.g. a hold being
   * converted into a debit).
   */
  Transaction write(Header header, List<EntryDraft> drafts, Map<UUID, Account> lockedAccounts) {
    Posting.Result result = Posting.apply(lockedAccounts, drafts);
    Transaction transaction =
        new Transaction(
            ids.next(),
            header.kind(),
            header.reference(),
            header.description(),
            header.reversesId(),
            header.holdId(),
            clock.instant(),
            result.entries());
    transactions.insert(transaction);
    accounts.updateBalances(result.accounts().values());
    outbox.append(event(transaction));
    metrics.transactionPosted(transaction.kind().name());
    return transaction;
  }

  static NewEvent event(Transaction transaction) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Entry entry : transaction.entries()) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("account_id", entry.accountId().toString());
      line.put("direction", entry.direction().name());
      line.put("amount", entry.amount());
      line.put("currency", entry.currency());
      line.put("balance_after", entry.balanceAfter());
      entries.add(line);
    }
    Map<String, Object> payload = new HashMap<>();
    payload.put("transaction_id", transaction.id().toString());
    payload.put("kind", transaction.kind().name());
    payload.put("reference", transaction.reference());
    payload.put(
        "reverses_id",
        transaction.reversesId() == null ? null : transaction.reversesId().toString());
    payload.put("hold_id", transaction.holdId() == null ? null : transaction.holdId().toString());
    payload.put("created_at", transaction.createdAt().toString());
    payload.put("entries", entries);
    return new NewEvent("transaction", transaction.id().toString(), "transaction.posted", payload);
  }
}
