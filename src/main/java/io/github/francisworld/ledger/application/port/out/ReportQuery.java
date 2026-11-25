package io.github.francisworld.ledger.application.port.out;

import io.github.francisworld.ledger.application.port.in.ReportUseCase.CurrencyTotals;
import java.util.List;

public interface ReportQuery {

  List<CurrencyTotals> totalsByCurrency();
}
