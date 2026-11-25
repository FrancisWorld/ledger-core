package io.github.francisworld.ledger.application.port.in;

import java.util.List;

/** Read-only integrity reporting over the whole ledger. */
public interface ReportUseCase {

  /**
   * Totals per currency. In a sound ledger the debit-normal and credit-normal totals are equal and
   * no account balance disagrees with the sum of its entries.
   */
  record CurrencyTotals(
      String currency, long debitNormalTotal, long creditNormalTotal, long driftedAccounts) {

    public boolean balanced() {
      return debitNormalTotal == creditNormalTotal && driftedAccounts == 0;
    }
  }

  record TrialBalance(List<CurrencyTotals> currencies) {

    public boolean balanced() {
      return currencies.stream().allMatch(CurrencyTotals::balanced);
    }
  }

  TrialBalance trialBalance();
}
