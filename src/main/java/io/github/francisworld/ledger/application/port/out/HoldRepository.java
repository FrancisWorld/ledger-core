package io.github.francisworld.ledger.application.port.out;

import io.github.francisworld.ledger.domain.Hold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository {

  void insert(Hold hold);

  Optional<Hold> find(UUID id);

  /** Reads the hold and takes a row lock held until the surrounding unit of work ends. */
  Optional<Hold> lock(UUID id);

  void update(Hold hold);

  /** Locks active holds expiring at or before {@code now}, skipping rows other workers hold. */
  List<Hold> lockExpired(Instant now, int limit);
}
