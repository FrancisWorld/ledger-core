package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.francisworld.ledger.domain.Scope;
import io.github.francisworld.ledger.support.AbstractIT;
import io.github.francisworld.ledger.support.TestApi.Resp;
import io.github.francisworld.ledger.support.TestKeys;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationIT extends AbstractIT {

  @Test
  void requestsWithoutCredentialsAreRejected() {
    Resp r = api.withToken(null).get("/v1/trial-balance");

    assertThat(r.status()).isEqualTo(401);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(r.body().get("code").asString()).isEqualTo("unauthenticated");
  }

  @Test
  void unknownMalformedAndRevokedKeysAreRejected() {
    assertThat(api.withToken("lck_doesnotexist").get("/v1/trial-balance").status()).isEqualTo(401);
    assertThat(api.withToken("not-even-prefixed").get("/v1/trial-balance").status()).isEqualTo(401);
    String key = TestKeys.issue("short-lived", EnumSet.of(Scope.REPORTS_READ));
    assertThat(api.withToken(key).get("/v1/trial-balance").status()).isEqualTo(200);

    TestKeys.revoke(key);

    assertThat(api.withToken(key).get("/v1/trial-balance").status()).isEqualTo(401);
  }

  @Test
  void aKeyOnlyWorksForItsOwnScopes() {
    String reader = TestKeys.issue("reports-only", EnumSet.of(Scope.REPORTS_READ));
    var readerApi = api.withToken(reader);

    assertThat(readerApi.get("/v1/trial-balance").status()).isEqualTo(200);
    Resp forbidden = readerApi.get("/v1/accounts/" + UUID.randomUUID());
    assertThat(forbidden.status()).isEqualTo(403);
    assertThat(forbidden.body().get("detail").asString()).contains("accounts:read");
    Resp write =
        readerApi.post(
            "/v1/accounts",
            uniqueKey(),
            Map.of("code", uniqueCode("x"), "name", "x", "type", "ASSET", "currency", "USD"));
    assertThat(write.status()).isEqualTo(403);
  }

  @Test
  void readScopeDoesNotGrantMoneyMovement() {
    UUID a = api.wallet(uniqueCode("a"));
    UUID b = api.wallet(uniqueCode("b"));
    String readOnly =
        TestKeys.issue("read-only", EnumSet.of(Scope.ACCOUNTS_READ, Scope.TRANSACTIONS_READ));

    Resp r = api.withToken(readOnly).transfer(uniqueKey(), a, b, 1);

    assertThat(r.status()).isEqualTo(403);
    assertThat(api.withToken(readOnly).get("/v1/accounts/" + a).status()).isEqualTo(200);
  }

  @Test
  void idempotencyKeysAreIsolatedBetweenClients() {
    UUID cash = api.cash(uniqueCode("cash"));
    UUID a = api.wallet(uniqueCode("a"));
    UUID b = api.wallet(uniqueCode("b"));
    api.deposit(cash, a, 1_000);
    String other = TestKeys.issue("other-client", EnumSet.allOf(Scope.class));
    String sharedKey = uniqueKey();

    Resp first = api.transfer(sharedKey, a, b, 100);
    Resp second = api.withToken(other).transfer(sharedKey, a, b, 100);

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.replayed())
        .as("a different client never sees another client's replay")
        .isNull();
    assertThat(second.body().get("id")).isNotEqualTo(first.body().get("id"));
    assertThat(api.balance(b)).isEqualTo(200);
  }

  @Test
  void healthAndMetricsLiveOnTheManagementPortWithoutCredentials() {
    assertThat(management.get("/actuator/health").status()).isEqualTo(200);
    assertThat(management.get("/actuator/prometheus").status()).isEqualTo(200);
    assertThat(api.withToken(null).get("/actuator/prometheus").status()).isEqualTo(404);
  }

  @Test
  void keysAreStoredHashedNeverInPlaintext() {
    String key = TestKeys.issue("hashed", EnumSet.of(Scope.REPORTS_READ));

    long plaintextRows =
        io.github.francisworld.ledger.support.PostgresTestSupport.ownerJdbc()
            .sql("select count(*) from api_keys where key_hash = :key")
            .param("key", key)
            .query(Long.class)
            .single();

    assertThat(plaintextRows).isZero();
  }
}
