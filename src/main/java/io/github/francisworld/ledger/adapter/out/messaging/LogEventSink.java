package io.github.francisworld.ledger.adapter.out.messaging;

import io.github.francisworld.ledger.application.port.out.EventSink;
import io.github.francisworld.ledger.application.port.out.OutboxStore.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default sink for local runs: writes each event to the application log. */
@Component
@ConditionalOnProperty(name = "ledger.outbox.sink", havingValue = "LOG", matchIfMissing = true)
class LogEventSink implements EventSink {

  private static final Logger LOG = LoggerFactory.getLogger(LogEventSink.class);

  @Override
  public void publish(Message message) {
    LOG.info(
        "event id={} type={} aggregate={}/{} payload={}",
        message.id(),
        message.eventType(),
        message.aggregateType(),
        message.aggregateId(),
        message.payload());
  }
}
