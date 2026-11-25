package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.out.IdempotencyStore;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import io.github.francisworld.ledger.domain.LedgerException;
import java.util.function.Supplier;

/**
 * Exactly-once execution of a request identified by an idempotency key.
 *
 * <p>The key row and the business writes share one unit of work. They commit or roll back together,
 * so a crash can never leave a key recorded without its effects (or the reverse). A concurrent
 * duplicate blocks on the key's unique index until the first attempt finishes, then replays its
 * stored response. Failed attempts roll back and leave no key behind, so they can be retried.
 */
public final class IdempotentExecutor {

  /** The HTTP-shaped response that is stored and replayed verbatim. */
  public record Response(int status, String body) {}

  public record Outcome(Response response, boolean replayed) {}

  private final IdempotencyStore store;
  private final UnitOfWork unitOfWork;
  private final LedgerMetrics metrics;

  public IdempotentExecutor(IdempotencyStore store, UnitOfWork unitOfWork, LedgerMetrics metrics) {
    this.store = store;
    this.unitOfWork = unitOfWork;
    this.metrics = metrics;
  }

  /**
   * @param fingerprint digest of what the client asked for; reusing a key with a different
   *     fingerprint is a client error
   */
  public Outcome execute(String key, String fingerprint, Supplier<Response> action) {
    return unitOfWork.execute(
        () -> {
          if (store.begin(key, fingerprint)) {
            Response response = action.get();
            store.complete(key, response.status(), response.body());
            return new Outcome(response, false);
          }
          IdempotencyStore.Record existing =
              store
                  .find(key)
                  .orElseThrow(() -> new IllegalStateException("idempotency key vanished: " + key));
          if (!existing.fingerprint().equals(fingerprint)) {
            throw LedgerException.conflict(
                "idempotency-key-reuse",
                "Idempotency-Key was already used with a different request");
          }
          metrics.idempotentReplay();
          return new Outcome(new Response(existing.status(), existing.body()), true);
        });
  }
}
