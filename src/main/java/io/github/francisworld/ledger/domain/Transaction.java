package io.github.francisworld.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An immutable, balanced group of entries. Once posted it can only be countered by a reversal. */
public record Transaction(
    UUID id,
    TransactionKind kind,
    String reference,
    String description,
    UUID reversesId,
    UUID holdId,
    Instant createdAt,
    List<Entry> entries) {

  public Transaction {
    entries = List.copyOf(entries);
  }
}
