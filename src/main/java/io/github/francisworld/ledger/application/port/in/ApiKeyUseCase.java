package io.github.francisworld.ledger.application.port.in;

import io.github.francisworld.ledger.domain.Principal;
import io.github.francisworld.ledger.domain.Scope;
import java.util.Optional;
import java.util.Set;

/** API-key authentication and issuing. */
public interface ApiKeyUseCase {

  /** The plaintext key is only available here, at creation; only its hash is stored. */
  record IssuedKey(Principal principal, String plaintext) {}

  /** Resolves a presented key to its principal; empty for unknown or revoked keys. */
  Optional<Principal> authenticate(String presentedKey);

  IssuedKey issue(String name, Set<Scope> scopes);
}
