package io.github.francisworld.ledger.domain;

/** Side of a double-entry line. */
public enum Direction {
  DEBIT,
  CREDIT;

  public Direction opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
