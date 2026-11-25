package io.github.francisworld.ledger.bootstrap;

import io.github.francisworld.ledger.application.port.in.HoldUseCase;
import io.github.francisworld.ledger.application.service.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic triggers for the outbox relay and the hold-expiry sweeper. */
@Component
class BackgroundJobs {

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundJobs.class);
  private static final int MAX_PASSES_PER_TICK = 20;

  private final OutboxRelay relay;
  private final HoldUseCase holds;
  private final LedgerProperties properties;

  BackgroundJobs(OutboxRelay relay, HoldUseCase holds, LedgerProperties properties) {
    this.relay = relay;
    this.holds = holds;
    this.properties = properties;
  }

  /** Keeps draining while batches come back full, so a backlog clears without waiting for ticks. */
  @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval:500ms}")
  void relayOutbox() {
    if (!properties.outbox().relayEnabled()) {
      return;
    }
    try {
      for (int pass = 0; pass < MAX_PASSES_PER_TICK; pass++) {
        if (relay.relayBatch() < properties.outbox().batchSize()) {
          return;
        }
      }
    } catch (RuntimeException e) {
      LOG.error("outbox relay pass failed", e);
    }
  }

  @Scheduled(fixedDelayString = "${ledger.holds.sweep-interval:15s}")
  void sweepHolds() {
    if (!properties.holds().sweeperEnabled()) {
      return;
    }
    try {
      int released = holds.expireDue();
      if (released > 0) {
        LOG.info("released {} expired holds", released);
      }
    } catch (RuntimeException e) {
      LOG.error("hold expiry sweep failed", e);
    }
  }
}
