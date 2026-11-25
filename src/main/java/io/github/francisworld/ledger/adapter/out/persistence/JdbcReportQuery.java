package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.in.ReportUseCase.CurrencyTotals;
import io.github.francisworld.ledger.application.port.out.ReportQuery;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReportQuery implements ReportQuery {

  private final JdbcClient jdbc;

  JdbcReportQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Debit-normal and credit-normal balances must sum to the same figure per currency (the
   * accounting equation). A drifted account is one whose stored balance differs from what replaying
   * its entries yields.
   */
  @Override
  public List<CurrencyTotals> totalsByCurrency() {
    return jdbc.sql(
            """
            with replayed as (
              select a.id, a.currency, a.type, a.balance,
                     coalesce(sum(case when e.direction = case when a.type in ('ASSET', 'EXPENSE')
                                                                then 'DEBIT' else 'CREDIT' end
                                       then e.amount else -e.amount end), 0) as replayed_balance
                from accounts a left join entries e on e.account_id = a.id
               group by a.id
            )
            select currency,
                   coalesce(sum(balance) filter (where type in ('ASSET', 'EXPENSE')), 0) as debit_normal,
                   coalesce(sum(balance) filter (where type not in ('ASSET', 'EXPENSE')), 0) as credit_normal,
                   count(*) filter (where balance <> replayed_balance) as drifted
              from replayed
             group by currency
             order by currency
            """)
        .query(
            (rs, n) ->
                new CurrencyTotals(
                    rs.getString("currency"),
                    rs.getLong("debit_normal"),
                    rs.getLong("credit_normal"),
                    rs.getLong("drifted")))
        .list();
  }
}
