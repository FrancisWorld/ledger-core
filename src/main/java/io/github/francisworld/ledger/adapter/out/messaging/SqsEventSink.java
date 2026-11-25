package io.github.francisworld.ledger.adapter.out.messaging;

import io.github.francisworld.ledger.application.port.out.EventSink;
import io.github.francisworld.ledger.application.port.out.OutboxStore.Message;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes outbox messages to an SQS queue. The outbox id travels as the {@code event_id}
 * attribute so consumers can drop the duplicates that at-least-once delivery allows.
 */
public final class SqsEventSink implements EventSink {

  private final SqsClient sqs;
  private final String queueUrl;

  public SqsEventSink(SqsClient sqs, String queueUrl) {
    this.sqs = sqs;
    this.queueUrl = queueUrl;
  }

  @Override
  public void publish(Message message) {
    sqs.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(message.payload())
            .messageAttributes(
                Map.of(
                    "event_id", attribute(Long.toString(message.id())),
                    "event_type", attribute(message.eventType()),
                    "aggregate_id", attribute(message.aggregateId())))
            .build());
  }

  private static MessageAttributeValue attribute(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }
}
