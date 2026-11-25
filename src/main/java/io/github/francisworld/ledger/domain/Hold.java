package io.github.francisworld.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A reservation of funds on an account (an authorization). It can be captured once, for at most the
 * reserved amount, or released; whatever is not captured goes back to the spendable balance.
 */
public record Hold(
    UUID id,
    UUID accountId,
    long amount,
    String currency,
    HoldStatus status,
    String reference,
    Instant expiresAt,
    Long capturedAmount,
    UUID captureTransactionId,
    Instant createdAt,
    Instant updatedAt) {

  public static Hold place(
      UUID id,
      UUID accountId,
      long amount,
      String currency,
      String reference,
      Instant expiresAt,
      Instant now) {
    if (amount <= 0 || amount > Posting.MAX_AMOUNT) {
      throw LedgerException.invalid(
          "invalid-amount", "Hold amount must be positive and within limits");
    }
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      throw LedgerException.invalid("invalid-expiry", "Hold expiry must be in the future");
    }
    return new Hold(
        id,
        accountId,
        amount,
        currency,
        HoldStatus.ACTIVE,
        reference,
        expiresAt,
        null,
        null,
        now,
        now);
  }

  public boolean isExpiredAt(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  /** Marks the hold captured for {@code captureAmount}; only an unexpired ACTIVE hold qualifies. */
  public Hold capture(long captureAmount, Instant now) {
    requireActive(now);
    if (captureAmount <= 0 || captureAmount > amount) {
      throw LedgerException.unprocessable(
          "invalid-capture-amount",
          "Capture amount must be between 1 and the held amount " + amount);
    }
    return new Hold(
        id,
        accountId,
        amount,
        currency,
        HoldStatus.CAPTURED,
        reference,
        expiresAt,
        captureAmount,
        null,
        createdAt,
        now);
  }

  /** Records which ledger transaction settled this captured hold. */
  public Hold settledBy(UUID transactionId) {
    return new Hold(
        id,
        accountId,
        amount,
        currency,
        status,
        reference,
        expiresAt,
        capturedAmount,
        transactionId,
        createdAt,
        updatedAt);
  }

  /** Releasing is allowed even after expiry: it only gives funds back. */
  public Hold release(Instant now) {
    if (status != HoldStatus.ACTIVE) {
      throw LedgerException.conflict("hold-not-active", "Hold is already " + status);
    }
    return withStatus(HoldStatus.RELEASED, now);
  }

  /** Terminal transition used by the sweeper once the expiry time has passed. */
  public Hold expire(Instant now) {
    if (status != HoldStatus.ACTIVE || !isExpiredAt(now)) {
      throw new IllegalStateException("only an ACTIVE hold past its expiry can expire");
    }
    return withStatus(HoldStatus.EXPIRED, now);
  }

  private void requireActive(Instant now) {
    if (status != HoldStatus.ACTIVE) {
      throw LedgerException.conflict("hold-not-active", "Hold is already " + status);
    }
    if (isExpiredAt(now)) {
      throw LedgerException.conflict("hold-expired", "Hold expired at " + expiresAt);
    }
  }

  private Hold withStatus(HoldStatus next, Instant now) {
    return new Hold(
        id,
        accountId,
        amount,
        currency,
        next,
        reference,
        expiresAt,
        capturedAmount,
        captureTransactionId,
        createdAt,
        now);
  }
}
