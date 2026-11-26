package io.github.francisworld.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account. {@code balance} is expressed in minor units on the account's normal side (a
 * credit to a liability grows it, a debit to an asset grows it). {@code held} is the part of the
 * balance reserved by active holds, so spendable funds are {@code balance - held}.
 *
 * <p>Instances are immutable; every mutation returns a new value so that callers can compute a
 * whole posting before anything is persisted.
 */
public record Account(
    UUID id,
    String code,
    String name,
    AccountType type,
    String currency,
    boolean allowNegative,
    long balance,
    long held,
    Instant createdAt) {

  public Account {
    Currencies.require(currency);
    if (held < 0) {
      throw new IllegalStateException("held must not be negative");
    }
  }

  public static Account open(
      UUID id,
      String code,
      String name,
      AccountType type,
      String currency,
      boolean allowNegative,
      Instant now) {
    return new Account(id, code, name, type, currency, allowNegative, 0, 0, now);
  }

  /**
   * Spendable funds, {@code balance - held}, computed with checked arithmetic. On an overdraftable
   * account whose balance sits near {@code Long.MIN_VALUE} the exact difference does not fit in a
   * long, so it saturates at {@code Long.MIN_VALUE} (still "deeply negative") instead of wrapping
   * around to a large positive number.
   */
  public long available() {
    try {
      return Math.subtractExact(balance, held);
    } catch (ArithmeticException e) {
      return Long.MIN_VALUE;
    }
  }

  public Direction normalSide() {
    return type.normalSide();
  }

  /**
   * Applies one ledger line to the balance. The overdraft rule is deliberately not checked here: a
   * journal is judged on its final state, so line order inside a transaction never matters. Call
   * {@link #requireSolvent()} once the whole journal has been applied.
   */
  public Account apply(Direction direction, long amount) {
    long signed = direction == normalSide() ? amount : -amount;
    long next;
    try {
      next = Math.addExact(balance, signed);
    } catch (ArithmeticException e) {
      throw LedgerException.unprocessable("amount-overflow", "Balance would overflow");
    }
    return new Account(id, code, name, type, currency, allowNegative, next, held, createdAt);
  }

  /**
   * Reserves funds for a hold. A hold always needs the funds to exist: {@code allowNegative} lets
   * debits overdraw the account but never lets a hold reserve money the account does not have.
   */
  public Account reserve(long amount) {
    if (normalSide() != Direction.CREDIT) {
      throw LedgerException.unprocessable(
          "hold-not-supported", "Holds are only supported on credit-normal accounts");
    }
    long nextHeld;
    try {
      nextHeld = Math.addExact(held, amount);
    } catch (ArithmeticException e) {
      throw LedgerException.unprocessable("amount-overflow", "Held amount would overflow");
    }
    if (nextHeld > balance) {
      throw LedgerException.unprocessable(
          "insufficient-funds",
          "Account %s cannot reserve %d: balance %d, already held %d"
              .formatted(code, amount, balance, held));
    }
    return new Account(id, code, name, type, currency, allowNegative, balance, nextHeld, createdAt);
  }

  /** Gives reserved funds back to the spendable balance. */
  public Account unreserve(long amount) {
    if (amount > held) {
      throw new IllegalStateException("cannot release more than is held");
    }
    return new Account(
        id, code, name, type, currency, allowNegative, balance, held - amount, createdAt);
  }

  /**
   * Fails with {@code insufficient-funds} when the spendable balance is negative and not allowed.
   */
  public Account requireSolvent() {
    if (!allowNegative && available() < 0) {
      throw LedgerException.unprocessable(
          "insufficient-funds",
          "Account %s would be left with %d available, which is not allowed"
              .formatted(code, available()));
    }
    return this;
  }
}
