package io.github.francisworld.ledger.application.port.out;

import io.github.francisworld.ledger.domain.Principal;
import java.util.Optional;

public interface ApiKeyRepository {

  /** Looks up an active (not revoked) key by the SHA-256 hex digest of its plaintext. */
  Optional<Principal> findActiveByHash(String hash);

  void insert(Principal principal, String hash);
}
