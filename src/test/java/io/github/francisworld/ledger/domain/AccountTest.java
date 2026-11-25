package io.github.francisworld.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountTest {

  @Test
  void normalSideDecidesWhetherADebitGrowsOrShrinksTheBalance() {
    Account asset = TestAccounts.cash(100);
    Account liability = TestAccounts.wallet(100);

    assertThat(asset.apply(Direction.DEBIT, 40).balance()).isEqualTo(140);
    assertThat(asset.apply(Direction.CREDIT, 40).balance()).isEqualTo(60);
    assertThat(liability.apply(Direction.CREDIT, 40).balance()).isEqualTo(140);
    assertThat(liability.apply(Direction.DEBIT, 40).balance()).isEqualTo(60);
  }

  @Test
  void spendableFundsExcludeHeldAmounts() {
    Account wallet = TestAccounts.wallet(100).reserve(30);

    assertThat(wallet.available()).isEqualTo(70);
    assertThatThrownBy(() -> wallet.apply(Direction.DEBIT, 80).requireSolvent())
        .isInstanceOfSatisfying(
            LedgerException.class, e -> assertThat(e.code()).isEqualTo("insufficient-funds"));
    assertThat(wallet.apply(Direction.DEBIT, 70).requireSolvent().available()).isZero();
  }

  @Test
  void reservingMoreThanAvailableIsRejected() {
    assertThatThrownBy(() -> TestAccounts.wallet(50).reserve(51))
        .isInstanceOfSatisfying(
            LedgerException.class, e -> assertThat(e.code()).isEqualTo("insufficient-funds"));
  }

  @Test
  void accountsThatAllowNegativeMayOverdraw() {
    Account cash = TestAccounts.cash(0).apply(Direction.CREDIT, 500);

    assertThat(cash.requireSolvent().balance()).isEqualTo(-500);
  }

  @Test
  void holdsAreOnlySupportedOnCreditNormalAccounts() {
    assertThatThrownBy(() -> TestAccounts.cash(100).reserve(10))
        .isInstanceOfSatisfying(
            LedgerException.class, e -> assertThat(e.code()).isEqualTo("hold-not-supported"));
  }

  @Test
  void balanceOverflowIsReportedNotWrapped() {
    Account rich = TestAccounts.wallet(Long.MAX_VALUE - 1);

    assertThatThrownBy(() -> rich.apply(Direction.CREDIT, 2))
        .isInstanceOfSatisfying(
            LedgerException.class, e -> assertThat(e.code()).isEqualTo("amount-overflow"));
  }

  @Test
  void rejectsUnknownCurrencies() {
    assertThatThrownBy(() -> TestAccounts.of(AccountType.ASSET, "XXQ", false, 0))
        .isInstanceOfSatisfying(
            LedgerException.class, e -> assertThat(e.code()).isEqualTo("invalid-currency"));
  }
}
