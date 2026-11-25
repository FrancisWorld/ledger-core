package io.github.francisworld.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** Builders shared by the domain tests. */
final class TestAccounts {

  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private TestAccounts() {}

  static Account wallet(long balance) {
    return new Account(
        UUID.randomUUID(),
        "w-" + UUID.randomUUID(),
        "wallet",
        AccountType.LIABILITY,
        "USD",
        false,
        balance,
        0,
        NOW);
  }

  static Account cash(long balance) {
    return new Account(
        UUID.randomUUID(),
        "c-" + UUID.randomUUID(),
        "cash",
        AccountType.ASSET,
        "USD",
        true,
        balance,
        0,
        NOW);
  }

  static Account of(AccountType type, String currency, boolean allowNegative, long balance) {
    return new Account(
        UUID.randomUUID(),
        "a-" + UUID.randomUUID(),
        "acct",
        type,
        currency,
        allowNegative,
        balance,
        0,
        NOW);
  }
}
