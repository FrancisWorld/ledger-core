package io.github.francisworld.ledger.domain;

import java.util.Currency;

/**
 * Validation of ISO 4217 currency codes. Amounts are always integer minor units of the currency.
 */
public final class Currencies {

  private Currencies() {}

  /** Returns the code unchanged when it is a known ISO 4217 code, otherwise fails. */
  public static String require(String code) {
    if (code == null || code.length() != 3) {
      throw LedgerException.invalid(
          "invalid-currency", "Currency must be a 3-letter ISO 4217 code");
    }
    try {
      Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      throw LedgerException.invalid("invalid-currency", "Unknown ISO 4217 currency: " + code);
    }
    return code;
  }
}
