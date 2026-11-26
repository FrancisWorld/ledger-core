package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.application.service.IdempotentExecutor;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
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
  private static final int MAX_KEY_LENGTH = 200;

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
    String scopedKey = principalId() + ":" + key;
    String fingerprint = sha256(operation + "\n" + json.writeValueAsString(request));
    IdempotentExecutor.Outcome outcome =
        executor.execute(
            scopedKey,
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

  /** Keys are namespaced per API key so one client can never replay another's responses. */
  private static String principalId() {
    Object principal =
        RequestContextHolder.currentRequestAttributes()
            .getAttribute(ApiKeyInterceptor.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (principal instanceof Principal p) {
      return p.keyId().toString();
    }
    throw LedgerException.unauthenticated("No authenticated principal");
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
