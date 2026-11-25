package io.github.francisworld.ledger.bootstrap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger")
public record LedgerProperties(@DefaultValue Outbox outbox, @DefaultValue Holds holds) {

  /** Transactional-outbox relay settings. */
  public record Outbox(
      @DefaultValue("true") boolean relayEnabled,
      @DefaultValue("LOG") Sink sink,
      @DefaultValue("500ms") Duration pollInterval,
      @DefaultValue("50") int batchSize,
      @DefaultValue("10") int maxAttempts,
      @DefaultValue("1s") Duration baseBackoff,
      @DefaultValue("5m") Duration maxBackoff,
      @DefaultValue Sqs sqs) {}

  public enum Sink {
    LOG,
    SQS
  }

  /** Only read when {@code sink} is SQS. A blank region and endpoint mean the SDK defaults. */
  public record Sqs(String queueUrl, String region, String endpoint) {}

  public record Holds(
      @DefaultValue("true") boolean sweeperEnabled, @DefaultValue("15s") Duration sweepInterval) {}
}
