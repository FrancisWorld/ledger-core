package io.github.francisworld.ledger.adapter.out.support;

import io.github.francisworld.ledger.application.port.out.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Time-ordered UUIDs (RFC 9562 version 7). Keeping the leading bits monotonic makes primary-key
 * B-tree inserts append-mostly instead of random, which matters for the append-heavy journal.
 */
@Component
class UuidV7Generator implements IdGenerator {

  private final SecureRandom random = new SecureRandom();
  private final Clock clock;

  UuidV7Generator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public UUID next() {
    long millis = clock.millis();
    long randA = random.nextLong() & 0xFFFL;
    long randB = random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
    long msb = (millis << 16) | (0x7L << 12) | randA;
    long lsb = (0x2L << 62) | randB;
    return new UUID(msb, lsb);
  }
}
