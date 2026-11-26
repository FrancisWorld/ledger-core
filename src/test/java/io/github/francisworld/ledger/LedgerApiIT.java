package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.francisworld.ledger.support.AbstractIT;
import io.github.francisworld.ledger.support.TestApi.Resp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LedgerApiIT extends AbstractIT {

  private UUID cash;
  private UUID alice;
  private UUID bob;

  private void fundedWorld() {
    cash = api.cash(uniqueCode("cash"));
    alice = api.wallet(uniqueCode("alice"));
    bob = api.wallet(uniqueCode("bob"));
    api.deposit(cash, alice, 10_000);
  }

  @Test
  void transferMovesMoneyAndReportsBalancesHoldsAndAvailable() {
    fundedWorld();

    Resp transfer = api.transfer(uniqueKey(), alice, bob, 2_500);

    assertThat(transfer.status()).isEqualTo(201);
    assertThat(transfer.body().get("kind").asString()).isEqualTo("TRANSFER");
    assertThat(transfer.body().get("entries")).hasSize(2);
    JsonNode a = api.account(alice);
    assertThat(a.get("balance").asLong()).isEqualTo(7_500);
    assertThat(a.get("available").asLong()).isEqualTo(7_500);
    assertThat(api.balance(bob)).isEqualTo(2_500);
  }

  @Test
  void insufficientFundsIsAProblemJsonAndChangesNothing() {
    fundedWorld();

    Resp r = api.transfer(uniqueKey(), alice, bob, 10_001);

    assertThat(r.status()).isEqualTo(422);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(r.body().get("code").asString()).isEqualTo("insufficient-funds");
    assertThat(r.body().get("type").asString())
        .isEqualTo("urn:ledger-core:problem:insufficient-funds");
    assertThat(api.balance(alice)).isEqualTo(10_000);
    assertThat(api.balance(bob)).isZero();
  }

  @Test
  void validationFailuresListTheOffendingFields() {
    fundedWorld();

    Resp r =
        api.post(
            "/v1/transfers",
            uniqueKey(),
            Map.of(
                "source_account_id",
                alice.toString(),
                "destination_account_id",
                bob.toString(),
                "amount",
                -5,
                "currency",
                "usd"));

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(r.body().get("code").asString()).isEqualTo("validation-failed");
    assertThat(r.body().get("errors").toString()).contains("amount").contains("currency");
  }

  @Test
  void missingOrMalformedIdempotencyKeyIsRejected() {
    fundedWorld();
    Map<String, Object> body =
        Map.of(
            "source_account_id",
            alice.toString(),
            "destination_account_id",
            bob.toString(),
            "amount",
            1,
            "currency",
            "USD");

    assertThat(api.post("/v1/transfers", null, body).status()).isEqualTo(400);
    Resp spaced = api.post("/v1/transfers", "has space", body);
    assertThat(spaced.status()).isEqualTo(400);
    assertThat(spaced.body().get("code").asString()).isEqualTo("invalid-idempotency-key");
  }

  @Test
  void malformedJsonAndUnknownResourcesUseProblemJson() {
    Resp bad = api.post("/v1/accounts", uniqueKey(), "{not json");
    Resp missing = api.get("/v1/accounts/" + UUID.randomUUID());

    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.contentType()).startsWith("application/problem+json");
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.body().get("code").asString()).isEqualTo("account-not-found");
  }

  @Test
  void duplicateAccountCodeConflicts() {
    String code = uniqueCode("dup");
    api.wallet(code);

    Resp r =
        api.post(
            "/v1/accounts",
            uniqueKey(),
            Map.of("code", code, "name", "again", "type", "LIABILITY", "currency", "USD"));

    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body().get("code").asString()).isEqualTo("duplicate-account-code");
  }

  @Test
  void transferBetweenDifferentCurrenciesIsRejected() {
    fundedWorld();
    UUID eur = api.openAccount(uniqueCode("eur"), "LIABILITY", "EUR", false);

    Resp r = api.transfer(uniqueKey(), alice, eur, 100);

    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().get("code").asString()).isEqualTo("currency-mismatch");
  }

  @Test
  void unbalancedJournalIsRejected() {
    fundedWorld();

    Resp r =
        api.post(
            "/v1/transactions",
            uniqueKey(),
            Map.of(
                "lines",
                List.of(
                    Map.of("account_id", cash.toString(), "direction", "DEBIT", "amount", 100),
                    Map.of("account_id", alice.toString(), "direction", "CREDIT", "amount", 90))));

    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().get("code").asString()).isEqualTo("unbalanced-transaction");
  }

  @Test
  void statementShowsRunningBalancesAndPaginatesWithAKeysetCursor() {
    fundedWorld();
    for (int i = 0; i < 4; i++) {
      assertThat(api.transfer(uniqueKey(), alice, bob, 100).status()).isEqualTo(201);
    }

    Resp first = api.get("/v1/accounts/" + alice + "/statement?limit=2");
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().get("lines")).hasSize(2);
    long cursor = first.body().get("next_cursor").asLong();
    Resp second = api.get("/v1/accounts/" + alice + "/statement?limit=10&after=" + cursor);

    JsonNode lines = second.body().get("lines");
    assertThat(lines).hasSize(3);
    assertThat(second.body().get("next_cursor").isNull()).isTrue();
    assertThat(lines.get(0).get("balance_after").asLong()).isEqualTo(9_800);
    assertThat(lines.get(2).get("balance_after").asLong()).isEqualTo(9_600);
    assertThat(lines.get(2).get("direction").asString()).isEqualTo("DEBIT");
    assertThat(api.get("/v1/accounts/" + alice + "/statement?limit=0").status()).isEqualTo(400);
  }

  @Test
  void holdsReduceAvailableFundsAndCaptureSettlesPartOfThem() {
    fundedWorld();

    Resp hold =
        api.post(
            "/v1/holds",
            uniqueKey(),
            Map.of(
                "account_id",
                alice.toString(),
                "amount",
                4_000,
                "currency",
                "USD",
                "reference",
                "order-7"));
    assertThat(hold.status()).isEqualTo(201);
    String holdId = hold.body().get("id").asString();
    JsonNode held = api.account(alice);
    assertThat(held.get("balance").asLong()).isEqualTo(10_000);
    assertThat(held.get("held").asLong()).isEqualTo(4_000);
    assertThat(held.get("available").asLong()).isEqualTo(6_000);

    // Held funds cannot be spent elsewhere.
    assertThat(api.transfer(uniqueKey(), alice, bob, 6_001).status()).isEqualTo(422);

    Resp capture =
        api.post(
            "/v1/holds/" + holdId + "/capture",
            uniqueKey(),
            Map.of("destination_account_id", bob.toString(), "amount", 3_000));
    assertThat(capture.status()).isEqualTo(200);
    assertThat(capture.body().get("hold").get("status").asString()).isEqualTo("CAPTURED");
    assertThat(capture.body().get("transaction").get("kind").asString()).isEqualTo("CAPTURE");
    JsonNode after = api.account(alice);
    assertThat(after.get("balance").asLong()).isEqualTo(7_000);
    assertThat(after.get("held").asLong()).isZero();
    assertThat(api.balance(bob)).isEqualTo(3_000);

    Resp again =
        api.post(
            "/v1/holds/" + holdId + "/capture",
            uniqueKey(),
            Map.of("destination_account_id", bob.toString()));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().get("code").asString()).isEqualTo("hold-not-active");
  }

  @Test
  void releaseFreesTheFundsAndOverHoldingIsRejected() {
    fundedWorld();
    Resp hold =
        api.post(
            "/v1/holds",
            uniqueKey(),
            Map.of("account_id", alice.toString(), "amount", 9_000, "currency", "USD"));

    Resp tooMuch =
        api.post(
            "/v1/holds",
            uniqueKey(),
            Map.of("account_id", alice.toString(), "amount", 1_001, "currency", "USD"));
    assertThat(tooMuch.status()).isEqualTo(422);
    assertThat(tooMuch.body().get("code").asString()).isEqualTo("insufficient-funds");

    Resp released =
        api.post("/v1/holds/" + hold.body().get("id").asString() + "/release", uniqueKey(), "");
    assertThat(released.status()).isEqualTo(200);
    assertThat(released.body().get("status").asString()).isEqualTo("RELEASED");
    JsonNode a = api.account(alice);
    assertThat(a.get("held").asLong()).isZero();
    assertThat(a.get("available").asLong()).isEqualTo(10_000);
  }

  @Test
  void reversalRestoresBalancesAndCanOnlyHappenOnce() {
    fundedWorld();
    Resp transfer = api.transfer(uniqueKey(), alice, bob, 1_000);
    String id = transfer.body().get("id").asString();

    Resp reversal =
        api.post(
            "/v1/transactions/" + id + "/reversal",
            uniqueKey(),
            Map.of("reason", "customer request"));

    assertThat(reversal.status()).isEqualTo(201);
    assertThat(reversal.body().get("kind").asString()).isEqualTo("REVERSAL");
    assertThat(reversal.body().get("reverses_id").asString()).isEqualTo(id);
    assertThat(api.balance(alice)).isEqualTo(10_000);
    assertThat(api.balance(bob)).isZero();

    Resp twice =
        api.post("/v1/transactions/" + id + "/reversal", uniqueKey(), Map.of("reason", "again"));
    assertThat(twice.status()).isEqualTo(409);
    assertThat(twice.body().get("code").asString()).isEqualTo("already-reversed");

    Resp ofReversal =
        api.post(
            "/v1/transactions/" + reversal.body().get("id").asString() + "/reversal",
            uniqueKey(),
            Map.of("reason", "undo the undo"));
    assertThat(ofReversal.status()).isEqualTo(409);
    assertThat(ofReversal.body().get("code").asString()).isEqualTo("cannot-reverse-reversal");
  }

  @Test
  void reversalCannotOverdrawTheAccountItWouldDebit() {
    fundedWorld();
    Resp transfer = api.transfer(uniqueKey(), alice, bob, 1_000);
    assertThat(api.transfer(uniqueKey(), bob, alice, 1_000).status()).isEqualTo(201);
    UUID carol = api.wallet(uniqueCode("carol"));
    // bob spends everything he can, then the original transfer cannot be undone
    assertThat(api.transfer(uniqueKey(), alice, bob, 1_000).status()).isEqualTo(201);
    assertThat(api.transfer(uniqueKey(), bob, carol, 1_000).status()).isEqualTo(201);

    Resp r =
        api.post(
            "/v1/transactions/" + transfer.body().get("id").asString() + "/reversal",
            uniqueKey(),
            Map.of("reason", "too late"));

    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().get("code").asString()).isEqualTo("insufficient-funds");
  }

  @Test
  void trialBalanceIsBalancedAndFreeOfDrift() {
    fundedWorld();
    api.transfer(uniqueKey(), alice, bob, 123);

    Resp r = api.get("/v1/trial-balance");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().get("balanced").asBoolean()).isTrue();
    for (JsonNode currency : r.body().get("currencies")) {
      assertThat(currency.get("drifted_accounts").asLong()).isZero();
      assertThat(currency.get("debit_normal_total").asLong())
          .isEqualTo(currency.get("credit_normal_total").asLong());
    }
  }

  @Test
  void exposesOpenApiHealthAndMetrics() {
    fundedWorld();

    Resp docs = api.get("/v3/api-docs");
    Resp health = management.get("/actuator/health");
    Resp metric = management.get("/actuator/metrics/ledger.transactions.posted");

    assertThat(docs.status()).isEqualTo(200);
    assertThat(docs.body().get("paths").toString()).contains("/v1/transfers").contains("/v1/holds");
    assertThat(health.body().get("status").asString()).isEqualTo("UP");
    assertThat(metric.status()).isEqualTo(200);
  }
}
