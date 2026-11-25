package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.application.service.IdempotentExecutor;
import io.github.francisworld.ledger.domain.LedgerException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapts {@link IdempotentExecutor} to HTTP: derives the request fingerprint from the operation and
 * the canonical re-serialization of the parsed body (so whitespace and key order do not matter),
 * and turns stored responses back into typed {@link ResponseEntity}s.
 */
@Component
class IdempotentHandler {

  static final String HEADER = "Idempotency-Key";
  static final String REPLAY_HEADER = "Idempotent-Replayed";
  private static final int MAX_KEY_LENGTH = 255;

  private final IdempotentExecutor executor;
  private final ObjectMapper json;

  IdempotentHandler(IdempotentExecutor executor, ObjectMapper json) {
    this.executor = executor;
    this.json = json;
  }

  <T> ResponseEntity<T> handle(
      String key,
      String operation,
      Object request,
      HttpStatus status,
      Class<T> responseType,
      Supplier<T> action) {
    requireValidKey(key);
    String fingerprint = sha256(operation + "\n" + json.writeValueAsString(request));
    IdempotentExecutor.Outcome outcome =
        executor.execute(
            key,
            fingerprint,
            () ->
                new IdempotentExecutor.Response(
                    status.value(), json.writeValueAsString(action.get())));
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.response().status());
    if (outcome.replayed()) {
      builder.header(REPLAY_HEADER, "true");
    }
    return builder.body(json.readValue(outcome.response().body(), responseType));
  }

  private static void requireValidKey(String key) {
    if (key == null
        || key.isBlank()
        || key.length() > MAX_KEY_LENGTH
        || !key.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
      throw LedgerException.invalid(
          "invalid-idempotency-key",
          "Idempotency-Key must be 1-"
              + MAX_KEY_LENGTH
              + " printable ASCII characters without spaces");
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
    }
  }
}
