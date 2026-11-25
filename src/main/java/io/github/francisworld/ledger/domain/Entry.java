package io.github.francisworld.ledger.domain;

import java.util.UUID;

/** A posted ledger line with the resulting account balance, as recorded in the journal. */
public record Entry(
    UUID accountId, Direction direction, long amount, String currency, long balanceAfter) {}
