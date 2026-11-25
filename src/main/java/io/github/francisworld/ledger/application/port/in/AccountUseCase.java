package io.github.francisworld.ledger.application.port.in;

import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.AccountType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Account management and statements. */
public interface AccountUseCase {

  record OpenAccount(
      String code, String name, AccountType type, String currency, boolean allowNegative) {}

  record StatementQuery(Instant from, Instant to, Long afterSeq, int limit) {}

  record StatementLine(
      long seq,
      UUID transactionId,
      String kind,
      String reference,
      String direction,
      long amount,
      String currency,
      long balanceAfter,
      Instant createdAt) {}

  record Statement(Account account, List<StatementLine> lines, Long nextCursor) {}

  Account open(OpenAccount command);

  Account get(UUID id);

  Statement statement(UUID accountId, StatementQuery query);
}
