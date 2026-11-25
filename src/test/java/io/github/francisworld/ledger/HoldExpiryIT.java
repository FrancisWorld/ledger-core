package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.francisworld.ledger.application.port.in.HoldUseCase;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.CaptureHold;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.PlaceHold;
import io.github.francisworld.ledger.domain.HoldStatus;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.support.AbstractIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HoldExpiryIT extends AbstractIT {

  @Autowired HoldUseCase holds;

  @Test
  void expiredHoldsCannotBeCapturedAndTheSweeperFreesTheirFunds() throws Exception {
    UUID cash = api.cash(uniqueCode("cash"));
    UUID wallet = api.wallet(uniqueCode("wallet"));
    UUID other = api.wallet(uniqueCode("other"));
    api.deposit(cash, wallet, 1_000);
    var hold = holds.place(new PlaceHold(wallet, 600, "USD", null, Instant.now().plusMillis(800)));
    assertThat(api.account(wallet).get("available").asLong()).isEqualTo(400);

    Thread.sleep(1_000);

    assertThatThrownBy(() -> holds.capture(new CaptureHold(hold.id(), other, null, null)))
        .isInstanceOfSatisfying(
            LedgerException.class, e -> assertThat(e.code()).isEqualTo("hold-expired"));
    assertThat(api.account(wallet).get("available").asLong()).isEqualTo(400);

    assertThat(holds.expireDue()).isGreaterThanOrEqualTo(1);

    assertThat(holds.get(hold.id()).status()).isEqualTo(HoldStatus.EXPIRED);
    assertThat(api.account(wallet).get("available").asLong()).isEqualTo(1_000);
    assertThat(holds.expireDue()).isZero();
  }
}
