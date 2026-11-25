package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.francisworld.ledger.application.port.out.EventSink;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import io.github.francisworld.ledger.application.service.OutboxRelay;
import io.github.francisworld.ledger.support.AbstractIT;
import io.github.francisworld.ledger.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class OutboxIT extends AbstractIT {

  /** Stands in for a JVM dying after the sink accepted a message but before the batch committed. */
  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;
  }

  @Autowired OutboxStore store;
  @Autowired UnitOfWork unitOfWork;
  @Autowired LedgerMetrics metrics;
  @Autowired JdbcClient jdbc;

  /** Runs ahead of real time so freshly written events are always due, however slow the test is. */
  private static final Clock AHEAD = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5));

  private OutboxRelay relay(EventSink sink, int batchSize, int maxAttempts) {
    return relay(AHEAD, sink, batchSize, maxAttempts);
  }

  private OutboxRelay relay(Clock clock, EventSink sink, int batchSize, int maxAttempts) {
    return new OutboxRelay(
        store,
        sink,
        unitOfWork,
        clock,
        metrics,
        new OutboxRelay.Settings(
            batchSize, maxAttempts, Duration.ofSeconds(1), Duration.ofMinutes(1)));
  }

  private long count(String status) {
    return jdbc.sql("select count(*) from outbox where status = :s")
        .param("s", status)
        .query(Long.class)
        .single();
  }

  /** Start every scenario from an empty queue so counts are exact. */
  @BeforeEach
  void drain() {
    OutboxRelay healthy = relay(m -> {}, 500, 5);
    while (healthy.relayBatch() > 0) {
      // keep draining
    }
    assertThat(count("PENDING")).isZero();
  }

  private void produceTransfers(int n) {
    UUID cash = api.cash(uniqueCode("cash"));
    UUID a = api.wallet(uniqueCode("a"));
    UUID b = api.wallet(uniqueCode("b"));
    api.deposit(cash, a, 1_000_000);
    for (int i = 0; i < n; i++) {
      assertThat(api.transfer(uniqueKey(), a, b, 1).status()).isEqualTo(201);
    }
  }

  @Test
  void eventsAreWrittenInTheSameTransactionAsTheirBusinessChange() throws Exception {
    UUID cash = api.cash(uniqueCode("cash"));
    UUID a = api.wallet(uniqueCode("a"));
    UUID b = api.wallet(uniqueCode("b"));
    api.deposit(cash, a, 100);
    assertThat(api.transfer(uniqueKey(), a, b, 40).status()).isEqualTo(201);

    long before = count("PENDING");
    assertThat(api.transfer(uniqueKey(), a, b, 1_000_000).status()).isEqualTo(422);

    assertThat(count("PENDING"))
        .as("a rolled-back request leaves no event behind")
        .isEqualTo(before);
    List<OutboxStore.Message> messages = new ArrayList<>();
    relay(messages::add, 100, 5).relayBatch();
    OutboxStore.Message posted =
        messages.stream()
            .filter(m -> m.eventType().equals("transaction.posted"))
            .filter(m -> json.readTree(m.payload()).get("kind").asString().equals("TRANSFER"))
            .findFirst()
            .orElseThrow();
    JsonNode payload = json.readTree(posted.payload());
    assertThat(posted.aggregateType()).isEqualTo("transaction");
    assertThat(payload.get("kind").asString()).isEqualTo("TRANSFER");
    assertThat(payload.get("entries")).hasSize(2);
    assertThat(payload.get("entries").get(0).get("amount").asLong()).isEqualTo(40);
    assertThat(payload.get("entries").get(0).get("balance_after").asLong()).isEqualTo(60);
    assertThat(messages.stream().map(OutboxStore.Message::eventType)).contains("account.opened");
  }

  @Test
  void concurrentRelaysDeliverEveryMessageExactlyOnce() throws Exception {
    produceTransfers(150);
    long pending = count("PENDING");
    Map<Long, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    Set<String> workers = ConcurrentHashMap.newKeySet();
    EventSink slowSink =
        message -> {
          Thread.sleep(2); // widen the window in which relays overlap
          workers.add(Thread.currentThread().getName());
          deliveries.computeIfAbsent(message.id(), k -> new AtomicInteger()).incrementAndGet();
        };

    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        futures.add(
            pool.submit(
                () -> {
                  OutboxRelay relay = relay(slowSink, 10, 5);
                  while (relay.relayBatch() > 0) {
                    // drain until empty
                  }
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(deliveries).hasSize((int) pending);
    assertThat(deliveries.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
    assertThat(workers.size()).as("relays really ran side by side").isGreaterThan(1);
    assertThat(count("PENDING")).isZero();
    assertThat(count("PUBLISHED")).isGreaterThanOrEqualTo(pending);
  }

  @Test
  void aCrashAfterDeliveryButBeforeCommitRedeliversInsteadOfLosingMessages() {
    produceTransfers(4);
    long pending = count("PENDING");
    List<Long> delivered = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger calls = new AtomicInteger();
    EventSink dyingSink =
        message -> {
          delivered.add(message.id());
          if (calls.incrementAndGet() == 3) {
            throw new SimulatedCrash();
          }
        };

    assertThatThrownBy(() -> relay(dyingSink, 100, 5).relayBatch())
        .isInstanceOf(SimulatedCrash.class);

    assertThat(delivered).hasSize(3);
    assertThat(count("PENDING")).as("nothing was marked published").isEqualTo(pending);

    List<Long> afterRestart = Collections.synchronizedList(new ArrayList<>());
    relay(m -> afterRestart.add(m.id()), 100, 5).relayBatch();

    assertThat(afterRestart).hasSize((int) pending);
    assertThat(afterRestart).containsAll(delivered); // delivered twice: at-least-once, never zero
    assertThat(count("PENDING")).isZero();
  }

  @Test
  void aFailingMessageBacksOffExponentiallyThenIsParkedWithoutBlockingOthers() {
    api.wallet(uniqueCode("poison"));
    api.wallet(uniqueCode("healthy"));
    List<String> published = Collections.synchronizedList(new ArrayList<>());
    EventSink picky =
        message -> {
          if (message.payload().contains("poison")) {
            throw new IllegalStateException("broker rejected message");
          }
          published.add(message.payload());
        };
    MutableClock clock = new MutableClock(Instant.now().plus(Duration.ofMinutes(5)));
    OutboxRelay relay = relay(clock, picky, 10, 3);

    assertThat(relay.relayBatch()).isEqualTo(1);
    assertThat(published).hasSize(1);
    assertThat(
            jdbc.sql("select attempts from outbox where status = 'PENDING'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);

    assertThat(relay.relayBatch()).as("still backing off").isZero();
    clock.advance(Duration.ofMillis(1_100));
    relay.relayBatch();
    assertThat(
            jdbc.sql("select attempts from outbox where status = 'PENDING'")
                .query(Integer.class)
                .single())
        .isEqualTo(2);

    clock.advance(Duration.ofSeconds(1));
    relay.relayBatch();
    assertThat(
            jdbc.sql("select attempts from outbox where status = 'PENDING'")
                .query(Integer.class)
                .single())
        .as("second backoff is 2s, one second is not enough")
        .isEqualTo(2);

    clock.advance(Duration.ofSeconds(2));
    relay.relayBatch();
    assertThat(count("DEAD")).isEqualTo(1);
    assertThat(count("PENDING")).isZero();
    assertThat(
            jdbc.sql("select last_error from outbox where status = 'DEAD'")
                .query(String.class)
                .single())
        .contains("broker rejected message");

    clock.advance(Duration.ofHours(1));
    assertThat(relay.relayBatch()).isZero();
  }
}
