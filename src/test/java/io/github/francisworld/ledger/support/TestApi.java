package io.github.francisworld.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Tiny JSON-over-HTTP client for black-box tests; never throws on non-2xx. */
public final class TestApi {

  public record Resp(int status, String contentType, String replayed, JsonNode body) {}

  private final String base;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  public TestApi(String base, ObjectMapper json) {
    this.base = base;
    this.json = json;
  }

  public Resp post(String path, String idempotencyKey, Object body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    body instanceof String s ? s : json.writeValueAsString(body)));
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request.build());
  }

  public Resp get(String path) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
  }

  private Resp send(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      String raw = response.body();
      return new Resp(
          response.statusCode(),
          response.headers().firstValue("Content-Type").orElse(""),
          response.headers().firstValue("Idempotent-Replayed").orElse(null),
          raw == null || raw.isBlank() ? null : json.readTree(raw));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- conveniences used by many tests

  public UUID openAccount(String code, String type, String currency, boolean allowNegative) {
    Resp r =
        post(
            "/v1/accounts",
            "open-" + code,
            Map.of(
                "code",
                code,
                "name",
                code,
                "type",
                type,
                "currency",
                currency,
                "allow_negative",
                allowNegative));
    assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
    return UUID.fromString(r.body().get("id").asString());
  }

  public UUID wallet(String code) {
    return openAccount(code, "LIABILITY", "USD", false);
  }

  public UUID cash(String code) {
    return openAccount(code, "ASSET", "USD", true);
  }

  /** Debit cash, credit wallet via a journal. */
  public void deposit(UUID cash, UUID wallet, long amount) {
    Resp r =
        post(
            "/v1/transactions",
            "dep-" + UUID.randomUUID(),
            Map.of(
                "lines",
                java.util.List.of(
                    Map.of("account_id", cash.toString(), "direction", "DEBIT", "amount", amount),
                    Map.of(
                        "account_id",
                        wallet.toString(),
                        "direction",
                        "CREDIT",
                        "amount",
                        amount))));
    assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
  }

  public Resp transfer(String key, UUID from, UUID to, long amount) {
    return post(
        "/v1/transfers",
        key,
        Map.of(
            "source_account_id",
            from.toString(),
            "destination_account_id",
            to.toString(),
            "amount",
            amount,
            "currency",
            "USD"));
  }

  public JsonNode account(UUID id) {
    Resp r = get("/v1/accounts/" + id);
    assertThat(r.status()).isEqualTo(200);
    return r.body();
  }

  public long balance(UUID id) {
    return account(id).get("balance").asLong();
  }
}
