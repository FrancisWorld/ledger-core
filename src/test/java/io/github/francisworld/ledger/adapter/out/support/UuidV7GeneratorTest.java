package io.github.francisworld.ledger.adapter.out.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.francisworld.ledger.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

  @Test
  void producesRfc9562Version7Identifiers() {
    UUID id = new UuidV7Generator(new MutableClock(Instant.now())).next();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  void embedsTheMillisecondTimestampInTheLeadingBits() {
    Instant at = Instant.parse("2026-03-04T05:06:07.123Z");

    UUID id = new UuidV7Generator(new MutableClock(at)).next();

    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(at.toEpochMilli());
  }

  @Test
  void laterTimestampsSortAfterEarlierOnes() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    UuidV7Generator generator = new UuidV7Generator(clock);

    UUID first = generator.next();
    clock.advance(Duration.ofMillis(1));
    UUID second = generator.next();

    assertThat(first.toString()).isLessThan(second.toString());
  }

  @Test
  void doesNotCollideWithinTheSameMillisecond() {
    UuidV7Generator generator = new UuidV7Generator(new MutableClock(Instant.now()));
    Set<UUID> seen = new HashSet<>();

    for (int i = 0; i < 10_000; i++) {
      assertThat(seen.add(generator.next())).isTrue();
    }
  }
}
