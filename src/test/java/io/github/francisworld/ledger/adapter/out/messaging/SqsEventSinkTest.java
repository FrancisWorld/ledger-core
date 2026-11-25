package io.github.francisworld.ledger.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.francisworld.ledger.application.port.out.OutboxStore.Message;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsEventSinkTest {

  private static final Message MESSAGE =
      new Message(42, "transaction", "tx-1", "transaction.posted", "{\"a\":1}", Instant.EPOCH, 0);

  @Test
  void sendsThePayloadWithIdentifyingAttributes() {
    SqsClient sqs = mock(SqsClient.class);
    new SqsEventSink(sqs, "http://queue.local/ledger").publish(MESSAGE);

    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(sqs).sendMessage(captor.capture());
    SendMessageRequest request = captor.getValue();
    assertThat(request.queueUrl()).isEqualTo("http://queue.local/ledger");
    assertThat(request.messageBody()).isEqualTo("{\"a\":1}");
    assertThat(request.messageAttributes().get("event_id").stringValue()).isEqualTo("42");
    assertThat(request.messageAttributes().get("event_type").stringValue())
        .isEqualTo("transaction.posted");
    assertThat(request.messageAttributes().get("aggregate_id").stringValue()).isEqualTo("tx-1");
  }

  @Test
  void brokerFailuresPropagateSoTheRelayCanRetry() {
    SqsClient sqs = mock(SqsClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenThrow(SqsException.builder().message("throttled").build());

    assertThatThrownBy(() -> new SqsEventSink(sqs, "q").publish(MESSAGE))
        .isInstanceOf(SqsException.class);
  }
}
