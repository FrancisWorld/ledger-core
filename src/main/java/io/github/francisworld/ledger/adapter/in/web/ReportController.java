package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.adapter.in.web.Api.CurrencyTotalsResponse;
import io.github.francisworld.ledger.adapter.in.web.Api.TrialBalanceResponse;
import io.github.francisworld.ledger.application.port.in.ReportUseCase;
import io.github.francisworld.ledger.application.port.in.ReportUseCase.TrialBalance;
import io.github.francisworld.ledger.domain.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/trial-balance")
@Tag(name = "Reports")
class ReportController {

  private final ReportUseCase reports;

  ReportController(ReportUseCase reports) {
    this.reports = reports;
  }

  @GetMapping
  @RequiresScope(Scope.REPORTS_READ)
  @Operation(
      summary = "Per-currency trial balance and drift check",
      description =
          "Replays every account's entries and compares with the stored balance; totals of debit-normal and credit-normal accounts must match.")
  TrialBalanceResponse trialBalance() {
    TrialBalance result = reports.trialBalance();
    return new TrialBalanceResponse(
        result.balanced(), result.currencies().stream().map(CurrencyTotalsResponse::from).toList());
  }
}
