package io.github.francisworld.ledger.application.port.out;

import java.util.UUID;

/** Source of identifiers for new aggregates. */
@FunctionalInterface
public interface IdGenerator {

  UUID next();
}
