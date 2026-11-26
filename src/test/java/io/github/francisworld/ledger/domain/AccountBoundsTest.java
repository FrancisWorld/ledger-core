package io.github.francisworld.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** Arithmetic at the edges of {@code long}: nothing may wrap around silently. */
class AccountBoundsTest {

  private static Account account(AccountType type, boolean allowNegative, long balance, long held) {
    return new Account(
        UUID.randomUUID(), "a", "a", type, "USD", allowNegative, balance, held, Instant.EPOCH);
  }

  private static String codeOf(Throwable t) {
    return ((LedgerException) t).code();
  }

  @Test
  void availableSaturatesInsteadOfWrappingWhenTheDifferenceDoesNotFit() {
    Account deepOverdraft = account(AccountType.LIABILITY, true, Long.MIN_VALUE + 5, 100);

    assertThat(deepOverdraft.available()).isEqualTo(Long.MIN_VALUE);
    assertThat(deepOverdraft.available()).isNegative();
  }

  @Test
  void availableIsExactWhereItFits() {
    assertThat(account(AccountType.LIABILITY, false, Long.MAX_VALUE, 0).available())
        .isEqualTo(Long.MAX_VALUE);
    assertThat(account(AccountType.LIABILITY, false, Long.MAX_VALUE, Long.MAX_VALUE).available())
        .isZero();
    assertThat(account(AccountType.LIABILITY, true, -1, 1).available()).isEqualTo(-2);
  }

  @Test
  void holdsNeedRealFundsEvenOnAccountsThatMayOverdraw() {
    Account overdrawn = account(AccountType.LIABILITY, true, -500, 0);

    assertThatThrownBy(() -> overdrawn.reserve(1))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("insufficient-funds"));
    assertThat(account(AccountType.LIABILITY, true, 100, 0).reserve(100).held()).isEqualTo(100);
  }

  @Test
  void reservingAtTheLongBoundaryIsExactOrRejected() {
    Account full = account(AccountType.LIABILITY, false, Long.MAX_VALUE, Long.MAX_VALUE - 1);

    assertThat(full.reserve(1).held()).isEqualTo(Long.MAX_VALUE);
    assertThatThrownBy(() -> full.reserve(2))
        .satisfies(t -> assertThat(codeOf(t)).isIn("amount-overflow", "insufficient-funds"));
  }

  @Test
  void applyDetectsOverflowInBothDirections() {
    assertThatThrownBy(
            () ->
                account(AccountType.LIABILITY, true, Long.MAX_VALUE, 0).apply(Direction.CREDIT, 1))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("amount-overflow"));
    assertThatThrownBy(
            () -> account(AccountType.LIABILITY, true, Long.MIN_VALUE, 0).apply(Direction.DEBIT, 1))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("amount-overflow"));
  }

  @Provide
  Arbitrary<Long> nearTheBounds() {
    return Arbitraries.oneOf(
        Arbitraries.longs().between(Long.MIN_VALUE, Long.MIN_VALUE + 1_000_000),
        Arbitraries.longs().between(Long.MAX_VALUE - 1_000_000, Long.MAX_VALUE),
        Arbitraries.longs().between(-1_000, 1_000));
  }

  @Property
  void availableNeverWrapsToTheWrongSign(
      @ForAll("nearTheBounds") long balance, @ForAll("nearTheBounds") long rawHeld) {
    long held = Math.abs(rawHeld == Long.MIN_VALUE ? 0 : rawHeld);
    Account account = account(AccountType.LIABILITY, true, balance, held);

    long available = account.available();

    // compare with exact arithmetic: a wrapped result would have the wrong sign
    java.math.BigInteger exact =
        java.math.BigInteger.valueOf(balance).subtract(java.math.BigInteger.valueOf(held));
    assertThat(Long.signum(available)).isEqualTo(exact.signum());
    if (exact.bitLength() < 64) {
      assertThat(available).isEqualTo(exact.longValueExact());
    } else {
      assertThat(available).isEqualTo(Long.MIN_VALUE);
    }
  }

  @Property
  void applyEitherReturnsTheExactSumOrFailsWithOverflow(
      @ForAll("nearTheBounds") long balance, @ForAll boolean debit, @ForAll long rawAmount) {
    long amount = Math.max(1, Math.abs(rawAmount % Posting.MAX_AMOUNT));
    Account account = account(AccountType.LIABILITY, true, balance, 0);
    java.math.BigInteger exact =
        java.math.BigInteger.valueOf(balance)
            .add(java.math.BigInteger.valueOf(debit ? -amount : amount));

    if (exact.bitLength() < 64) {
      assertThat(account.apply(debit ? Direction.DEBIT : Direction.CREDIT, amount).balance())
          .isEqualTo(exact.longValueExact());
    } else {
      assertThatThrownBy(() -> account.apply(debit ? Direction.DEBIT : Direction.CREDIT, amount))
          .satisfies(t -> assertThat(codeOf(t)).isEqualTo("amount-overflow"));
    }
  }

  @Property
  void aSuccessfulReserveNeverExceedsTheBalance(
      @ForAll("nearTheBounds") long balance, @ForAll long rawAmount) {
    long amount = Math.max(1, Math.abs(rawAmount % Posting.MAX_AMOUNT));
    Account account = account(AccountType.LIABILITY, true, balance, 0);

    try {
      Account reserved = account.reserve(amount);
      assertThat(reserved.held()).isLessThanOrEqualTo(reserved.balance());
    } catch (LedgerException e) {
      assertThat(e.code()).isIn("insufficient-funds", "amount-overflow");
    }
  }
}
