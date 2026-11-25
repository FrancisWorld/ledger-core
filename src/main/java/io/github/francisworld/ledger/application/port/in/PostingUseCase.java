package io.github.francisworld.ledger.application.port.in;

import io.github.francisworld.ledger.domain.Direction;
import io.github.francisworld.ledger.domain.Transaction;
import java.util.List;
import java.util.UUID;

/** Moving money: transfers, free-form journals and reversals. */
public interface PostingUseCase {

  record Transfer(
      UUID sourceAccountId,
      UUID destinationAccountId,
      long amount,
      String currency,
      String reference,
      String description) {}

  record JournalLine(UUID accountId, Direction direction, long amount) {}

  record Journal(List<JournalLine> lines, String reference, String description) {}

  Transaction transfer(Transfer command);

  Transaction post(Journal command);

  Transaction reverse(UUID transactionId, String reason);

  Transaction get(UUID transactionId);
}
