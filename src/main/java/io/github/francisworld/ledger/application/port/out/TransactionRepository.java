package io.github.francisworld.ledger.application.port.out;

import io.github.francisworld.ledger.application.port.in.AccountUseCase.StatementLine;
import io.github.francisworld.ledger.application.port.in.AccountUseCase.StatementQuery;
import io.github.francisworld.ledger.domain.Transaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

  /** Persists the transaction and its entries. Fails with a conflict if it is already reversed. */
  void insert(Transaction transaction);

  Optional<Transaction> find(UUID id);

  /**
   * Like {@link #find} but first takes a lock on the transaction held until the unit of work ends.
   * Reversals use it to serialize on the transaction being reversed, so a racing duplicate sees the
   * first reversal.
   */
  Optional<Transaction> lock(UUID id);

  boolean isReversed(UUID id);

  /** Returns up to {@code query.limit() + 1} lines so callers can detect a following page. */
  List<StatementLine> statement(UUID accountId, StatementQuery query);
}
