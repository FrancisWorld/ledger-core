package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.in.ReportUseCase;
import io.github.francisworld.ledger.application.port.out.ReportQuery;

public final class ReportService implements ReportUseCase {

  private final ReportQuery query;

  public ReportService(ReportQuery query) {
    this.query = query;
  }

  @Override
  public TrialBalance trialBalance() {
    return new TrialBalance(query.totalsByCurrency());
  }
}
