package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.francisworld.ledger.support.AbstractIT;
import io.github.francisworld.ledger.support.TestApi.Resp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyIT extends AbstractIT {

  private UUID alice;
  private UUID bob;

  private void world() {
    UUID cash = api.cash(uniqueCode("cash"));
    alice = api.wallet(uniqueCode("alice"));
    bob = api.wallet(uniqueCode("bob"));
    api.deposit(cash, alice, 10_000);
  }

  @Test
  void replayingAKeyReturnsTheOriginalResponseAndMovesMoneyOnce() {
    world();
    String key = uniqueKey();

    Resp first = api.transfer(key, alice, bob, 1_000);
    Resp second = api.transfer(key, alice, bob, 1_000);

    assertThat(first.status()).isEqualTo(201);
    assertThat(first.replayed()).isNull();
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.replayed()).isEqualTo("true");
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(api.balance(alice)).isEqualTo(9_000);
    assertThat(api.balance(bob)).isEqualTo(1_000);
  }

  @Test
  void sameKeyWithADifferentBodyIsAConflict() {
    world();
    String key = uniqueKey();
    api.transfer(key, alice, bob, 1_000);

    Resp r = api.transfer(key, alice, bob, 1_001);

    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body().get("code").asString()).isEqualTo("idempotency-key-reuse");
    assertThat(api.balance(alice)).isEqualTo(9_000);
  }

  @Test
  void sameKeyOnAnotherOperationIsAConflict() {
    world();
    String key = uniqueKey();
    api.transfer(key, alice, bob, 1_000);

    Resp r =
        api.post(
            "/v1/holds",
            key,
            Map.of("account_id", alice.toString(), "amount", 1_000, "currency", "USD"));

    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body().get("code").asString()).isEqualTo("idempotency-key-reuse");
  }

  @Test
  void whitespaceAndFieldOrderDoNotChangeTheFingerprint() {
    world();
    String key = uniqueKey();
    String compact =
        "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount\":500,\"currency\":\"USD\"}"
            .formatted(alice, bob);
    String reordered =
        "{ \"currency\": \"USD\",\n \"amount\": 500, \"destination_account_id\": \"%s\", \"source_account_id\": \"%s\" }"
            .formatted(bob, alice);

    Resp first = api.post("/v1/transfers", key, compact);
    Resp second = api.post("/v1/transfers", key, reordered);

    assertThat(second.status()).isEqualTo(201);
    assertThat(second.replayed()).isEqualTo("true");
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(api.balance(bob)).isEqualTo(500);
  }

  @Test
  void failedRequestsAreNotRememberedSoTheyCanBeRetried() {
    world();
    String key = uniqueKey();

    Resp rejected = api.transfer(key, alice, bob, 20_000);
    api.deposit(api.cash(uniqueCode("cash2")), alice, 15_000);
    Resp retried = api.transfer(key, alice, bob, 20_000);

    assertThat(rejected.status()).isEqualTo(422);
    assertThat(retried.status()).isEqualTo(201);
    assertThat(retried.replayed()).isNull();
  }

  @Test
  void concurrentDuplicatesExecuteExactlyOnceAndAllSeeTheSameResponse() throws Exception {
    world();
    String key = uniqueKey();
    int callers = 24;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Resp>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return api.transfer(key, alice, bob, 100);
                }));
      }
      start.countDown();
      List<Resp> responses = new ArrayList<>();
      for (Future<Resp> f : futures) {
        responses.add(f.get());
      }

      assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
      assertThat(responses.stream().filter(r -> r.replayed() == null)).hasSize(1);
      assertThat(responses.stream().map(r -> r.body().get("id").asString()).distinct()).hasSize(1);
    }
    assertThat(api.balance(alice)).isEqualTo(9_900);
    assertThat(api.balance(bob)).isEqualTo(100);
  }

  @Test
  void concurrentUseOfOneKeyWithDifferentBodiesLetsExactlyOneWin() throws Exception {
    world();
    String key = uniqueKey();
    int callers = 16;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Resp>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        long amount = 100 + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return api.transfer(key, alice, bob, amount);
                }));
      }
      start.countDown();
      List<Resp> responses = new ArrayList<>();
      for (Future<Resp> f : futures) {
        responses.add(f.get());
      }

      assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
      assertThat(responses.stream().filter(r -> r.status() == 409)).hasSize(callers - 1);
    }
    assertThat(api.balance(bob)).isBetween(100L, 115L);
    assertThat(api.balance(alice) + api.balance(bob)).isEqualTo(10_000);
  }
}
