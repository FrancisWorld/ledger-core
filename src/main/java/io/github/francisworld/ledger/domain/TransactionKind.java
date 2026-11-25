package io.github.francisworld.ledger.domain;

/** Why a transaction exists. */
public enum TransactionKind {
  TRANSFER,
  JOURNAL,
  CAPTURE,
  REVERSAL
}
