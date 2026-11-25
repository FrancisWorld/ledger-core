package io.github.francisworld.ledger.adapter.out.messaging;

import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class MicrometerLedgerMetrics implements LedgerMetrics {

  private final MeterRegistry registry;

  MicrometerLedgerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void transactionPosted(String kind) {
    registry.counter("ledger.transactions.posted", "kind", kind).increment();
  }

  @Override
  public void idempotentReplay() {
    registry.counter("ledger.idempotency.replays").increment();
  }

  @Override
  public void outboxPublished(int count) {
    if (count > 0) {
      registry.counter("ledger.outbox.published").increment(count);
    }
  }

  @Override
  public void outboxFailed(boolean dead) {
    registry.counter("ledger.outbox.failed", "dead", Boolean.toString(dead)).increment();
  }

  @Override
  public void holdExpired(int count) {
    registry.counter("ledger.holds.expired").increment(count);
  }
}
