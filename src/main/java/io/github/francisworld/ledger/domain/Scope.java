package io.github.francisworld.ledger.domain;

import java.util.Arrays;
import java.util.Optional;

/** Permissions an API key can carry; every HTTP operation requires exactly one. */
public enum Scope {
  ACCOUNTS_READ("accounts:read"),
  ACCOUNTS_WRITE("accounts:write"),
  TRANSACTIONS_READ("transactions:read"),
  TRANSACTIONS_WRITE("transactions:write"),
  HOLDS_READ("holds:read"),
  HOLDS_WRITE("holds:write"),
  REPORTS_READ("reports:read");

  private final String value;

  Scope(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static Optional<Scope> parse(String value) {
    return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
  }
}
