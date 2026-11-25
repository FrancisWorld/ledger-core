package io.github.francisworld.ledger.application.port.in;

import io.github.francisworld.ledger.domain.Hold;
import io.github.francisworld.ledger.domain.Transaction;
import java.time.Instant;
import java.util.UUID;

/** Authorization-style reservations of funds. */
public interface HoldUseCase {

  record PlaceHold(
      UUID accountId, long amount, String currency, String reference, Instant expiresAt) {}

  /** {@code amount} may be null to capture the full held amount. */
  record CaptureHold(UUID holdId, UUID destinationAccountId, Long amount, String reference) {}

  record Captured(Hold hold, Transaction transaction) {}

  Hold place(PlaceHold command);

  Hold get(UUID holdId);

  Captured capture(CaptureHold command);

  Hold release(UUID holdId);

  /** Releases every active hold past its expiry; returns how many were released. */
  int expireDue();
}
