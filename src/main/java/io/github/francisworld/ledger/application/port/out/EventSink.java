package io.github.francisworld.ledger.application.port.out;

/** Destination of outbox messages (a queue, a log, ...). Delivery is at-least-once. */
public interface EventSink {

  /** Delivers one message; throws if it could not be handed over. */
  void publish(OutboxStore.Message message) throws Exception;
}
