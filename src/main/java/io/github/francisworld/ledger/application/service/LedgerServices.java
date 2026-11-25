package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.out.AccountRepository;
import io.github.francisworld.ledger.application.port.out.HoldRepository;
import io.github.francisworld.ledger.application.port.out.IdGenerator;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.TransactionRepository;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import java.time.Clock;

/** Wires the money-moving services around one shared {@link JournalWriter}. */
public record LedgerServices(PostingService posting, HoldService holds, AccountService accounts) {

  public static LedgerServices create(
      AccountRepository accountRepository,
      TransactionRepository transactionRepository,
      HoldRepository holdRepository,
      OutboxStore outbox,
      UnitOfWork unitOfWork,
      IdGenerator ids,
      Clock clock,
      LedgerMetrics metrics) {
    JournalWriter writer =
        new JournalWriter(accountRepository, transactionRepository, outbox, ids, clock, metrics);
    return new LedgerServices(
        new PostingService(writer, transactionRepository, unitOfWork),
        new HoldService(
            holdRepository, accountRepository, outbox, writer, unitOfWork, ids, clock, metrics),
        new AccountService(
            accountRepository, transactionRepository, outbox, unitOfWork, ids, clock));
  }
}
