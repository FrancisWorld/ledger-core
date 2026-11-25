package io.github.francisworld.ledger.application.port.out;

/** Business-level telemetry hooks. */
public interface LedgerMetrics {

  void transactionPosted(String kind);

  void idempotentReplay();

  void outboxPublished(int count);

  void outboxFailed(boolean dead);

  void holdExpired(int count);
}
