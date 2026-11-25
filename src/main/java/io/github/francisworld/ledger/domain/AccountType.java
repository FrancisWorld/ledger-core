package io.github.francisworld.ledger.domain;

/** Classic five-way account classification; it decides which side increases the balance. */
public enum AccountType {
  ASSET(Direction.DEBIT),
  EXPENSE(Direction.DEBIT),
  LIABILITY(Direction.CREDIT),
  EQUITY(Direction.CREDIT),
  REVENUE(Direction.CREDIT);

  private final Direction normalSide;

  AccountType(Direction normalSide) {
    this.normalSide = normalSide;
  }

  /** The side on which an entry increases the account balance. */
  public Direction normalSide() {
    return normalSide;
  }
}
