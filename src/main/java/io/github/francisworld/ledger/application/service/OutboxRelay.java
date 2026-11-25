package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.out.EventSink;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains the transactional outbox. Each pass claims a batch with {@code FOR UPDATE SKIP LOCKED}, so
 * several relays (threads or instances) can run side by side without ever holding the same row.
 *
 * <p>Delivery is at-least-once: if the process dies after the sink accepted a message but before
 * the batch commits, the row is claimed again and re-delivered. Consumers must de-duplicate on the
 * event id. A message that keeps failing is retried with exponential backoff and finally parked.
 */
public final class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  /** Tuning knobs. */
  public record Settings(
      int batchSize, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {

    public Settings {
      if (batchSize < 1 || maxAttempts < 1) {
        throw new IllegalArgumentException("batchSize and maxAttempts must be positive");
      }
    }
  }

  private final OutboxStore store;
  private final EventSink sink;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final LedgerMetrics metrics;
  private final Settings settings;

  public OutboxRelay(
      OutboxStore store,
      EventSink sink,
      UnitOfWork unitOfWork,
      Clock clock,
      LedgerMetrics metrics,
      Settings settings) {
    this.store = store;
    this.sink = sink;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.metrics = metrics;
    this.settings = settings;
  }

  /** Runs one claim-deliver-commit pass and returns the number of messages delivered. */
  public int relayBatch() {
    return unitOfWork.execute(
        () -> {
          Instant now = clock.instant();
          List<OutboxStore.Message> batch = store.claim(now, settings.batchSize());
          List<Long> delivered = new ArrayList<>(batch.size());
          for (OutboxStore.Message message : batch) {
            try {
              sink.publish(message);
              delivered.add(message.id());
            } catch (Exception e) {
              recordFailure(message, now, e);
            }
          }
          store.markPublished(delivered, now);
          metrics.outboxPublished(delivered.size());
          return delivered.size();
        });
  }

  private void recordFailure(OutboxStore.Message message, Instant now, Exception cause) {
    int attempts = message.attempts() + 1;
    boolean dead = attempts >= settings.maxAttempts();
    Instant next = dead ? null : now.plus(backoff(attempts));
    store.markFailed(message.id(), attempts, next, String.valueOf(cause.getMessage()));
    metrics.outboxFailed(dead);
    LOG.warn(
        "outbox message {} ({}) failed attempt {}{}",
        message.id(),
        message.eventType(),
        attempts,
        dead ? ", parked as dead" : "",
        cause);
  }

  private Duration backoff(int attempts) {
    long factor = 1L << Math.min(attempts - 1, 20);
    Duration candidate = settings.baseBackoff().multipliedBy(factor);
    return candidate.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : candidate;
  }
}
