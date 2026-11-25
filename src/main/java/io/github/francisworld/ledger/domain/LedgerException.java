package io.github.francisworld.ledger.domain;

/**
 * Base type for every business-rule violation. Carries a stable machine-readable code and a coarse
 * {@link Kind} that adapters translate into their own protocol (HTTP status, for instance).
 */
public class LedgerException extends RuntimeException {

  /** Coarse classification of a failure, independent of any transport. */
  public enum Kind {
    INVALID,
    NOT_FOUND,
    CONFLICT,
    UNPROCESSABLE
  }

  private final Kind kind;
  private final String code;

  public LedgerException(Kind kind, String code, String message) {
    super(message);
    this.kind = kind;
    this.code = code;
  }

  public Kind kind() {
    return kind;
  }

  public String code() {
    return code;
  }

  public static LedgerException invalid(String code, String message) {
    return new LedgerException(Kind.INVALID, code, message);
  }

  public static LedgerException notFound(String code, String message) {
    return new LedgerException(Kind.NOT_FOUND, code, message);
  }

  public static LedgerException conflict(String code, String message) {
    return new LedgerException(Kind.CONFLICT, code, message);
  }

  public static LedgerException unprocessable(String code, String message) {
    return new LedgerException(Kind.UNPROCESSABLE, code, message);
  }
}
