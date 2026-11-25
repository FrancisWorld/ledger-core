package io.github.francisworld.ledger.domain;

import static io.github.francisworld.ledger.domain.TestAccounts.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldTest {

  private static Hold hold(Instant expiresAt) {
    return Hold.place(UUID.randomUUID(), UUID.randomUUID(), 100, "USD", "order-1", expiresAt, NOW);
  }

  private static String codeOf(Throwable t) {
    return ((LedgerException) t).code();
  }

  @Test
  void aNewHoldIsActive() {
    assertThat(hold(null).status()).isEqualTo(HoldStatus.ACTIVE);
  }

  @Test
  void captureMayTakeLessThanTheHeldAmountButNotMore() {
    Hold h = hold(null);

    Hold captured = h.capture(60, NOW.plusSeconds(1));
    assertThat(captured.status()).isEqualTo(HoldStatus.CAPTURED);
    assertThat(captured.capturedAmount()).isEqualTo(60);

    assertThatThrownBy(() -> h.capture(101, NOW))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-capture-amount"));
    assertThatThrownBy(() -> h.capture(0, NOW))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-capture-amount"));
  }

  @Test
  void terminalStatesCannotBeLeft() {
    Hold released = hold(null).release(NOW);

    assertThatThrownBy(() -> released.capture(10, NOW))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("hold-not-active"));
    assertThatThrownBy(() -> released.release(NOW))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("hold-not-active"));
  }

  @Test
  void anExpiredHoldCannotBeCapturedButCanStillBeReleased() {
    Hold h = hold(NOW.plus(Duration.ofMinutes(5)));
    Instant later = NOW.plus(Duration.ofMinutes(6));

    assertThatThrownBy(() -> h.capture(10, later))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("hold-expired"));
    assertThat(h.release(later).status()).isEqualTo(HoldStatus.RELEASED);
  }

  @Test
  void expiryRequiresTheDeadlineToHavePassed() {
    Hold h = hold(NOW.plus(Duration.ofMinutes(5)));

    assertThatThrownBy(() -> h.expire(NOW)).isInstanceOf(IllegalStateException.class);
    assertThat(h.expire(NOW.plus(Duration.ofMinutes(5))).status()).isEqualTo(HoldStatus.EXPIRED);
  }

  @Test
  void rejectsBadAmountsAndPastExpiry() {
    assertThatThrownBy(
            () -> Hold.place(UUID.randomUUID(), UUID.randomUUID(), 0, "USD", null, null, NOW))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-amount"));
    assertThatThrownBy(() -> hold(NOW.minusSeconds(1)))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-expiry"));
  }
}
