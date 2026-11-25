package io.github.francisworld.ledger.domain;

import java.util.UUID;

/** A requested ledger line, before the account state is known. */
public record EntryDraft(UUID accountId, Direction direction, long amount) {

  public EntryDraft {
    if (amount <= 0) {
      throw LedgerException.invalid("invalid-amount", "Entry amounts must be positive");
    }
  }
}
