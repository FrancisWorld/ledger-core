package io.github.francisworld.ledger.domain;

import java.util.Set;
import java.util.UUID;

/** An authenticated API client. */
public record Principal(UUID keyId, String name, Set<Scope> scopes) {

  public Principal {
    scopes = Set.copyOf(scopes);
  }

  public boolean has(Scope scope) {
    return scopes.contains(scope);
  }
}
