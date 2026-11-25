package io.github.francisworld.ledger.bootstrap;

import io.github.francisworld.ledger.adapter.out.messaging.SqsEventSink;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ledger.outbox.sink", havingValue = "SQS")
class SqsSinkConfiguration {

  @Bean(destroyMethod = "close")
  SqsClient sqsClient(LedgerProperties properties) {
    LedgerProperties.Sqs sqs = properties.outbox().sqs();
    SqsClientBuilder builder = SqsClient.builder();
    if (sqs.region() != null && !sqs.region().isBlank()) {
      builder.region(Region.of(sqs.region()));
    }
    if (sqs.endpoint() != null && !sqs.endpoint().isBlank()) {
      builder.endpointOverride(URI.create(sqs.endpoint()));
    }
    return builder.build();
  }

  @Bean
  SqsEventSink sqsEventSink(SqsClient client, LedgerProperties properties) {
    String queueUrl = properties.outbox().sqs().queueUrl();
    if (queueUrl == null || queueUrl.isBlank()) {
      throw new IllegalStateException(
          "ledger.outbox.sqs.queue-url is required when the sink is SQS");
    }
    return new SqsEventSink(client, queueUrl);
  }
}
