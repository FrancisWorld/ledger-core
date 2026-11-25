package io.github.francisworld.ledger.application.port.out;

import java.util.Optional;

public interface IdempotencyStore {

  record Record(String key, String fingerprint, int status, String body) {}

  /**
   * Claims the key for the current unit of work. Returns false when the key already exists; when a
   * concurrent unit of work holds it uncommitted this call blocks until that one finishes.
   */
  boolean begin(String key, String fingerprint);

  Optional<Record> find(String key);

  void complete(String key, int status, String body);
}
