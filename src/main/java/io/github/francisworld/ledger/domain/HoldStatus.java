package io.github.francisworld.ledger.domain;

/** Lifecycle of a hold. Everything except ACTIVE is terminal. */
public enum HoldStatus {
  ACTIVE,
  CAPTURED,
  RELEASED,
  EXPIRED
}
