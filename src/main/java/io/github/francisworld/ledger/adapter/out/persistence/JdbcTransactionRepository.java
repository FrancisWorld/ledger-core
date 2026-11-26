package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.in.AccountUseCase.StatementLine;
import io.github.francisworld.ledger.application.port.in.AccountUseCase.StatementQuery;
import io.github.francisworld.ledger.application.port.out.TransactionRepository;
import io.github.francisworld.ledger.domain.Direction;
import io.github.francisworld.ledger.domain.Entry;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Transaction;
import io.github.francisworld.ledger.domain.TransactionKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransactionRepository implements TransactionRepository {

  private final JdbcClient jdbc;

  JdbcTransactionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Transaction t) {
    try {
      jdbc.sql(
              """
              insert into transactions (id, kind, reference, description, reverses_id, hold_id, entry_count, created_at)
              values (:id, :kind, :reference, :description, :reversesId, :holdId, :entryCount, :createdAt)
              """)
          .param("id", t.id())
          .param("kind", t.kind().name())
          .param("reference", t.reference())
          .param("description", t.description())
          .param("reversesId", t.reversesId())
          .param("holdId", t.holdId())
          .param("entryCount", t.entries().size())
          .param("createdAt", Sql.ts(t.createdAt()))
          .update();
    } catch (DuplicateKeyException e) {
      if (Sql.violatedConstraint(e, "transactions_reverses_id_key")) {
        throw LedgerException.conflict(
            "already-reversed", "Transaction " + t.reversesId() + " was already reversed");
      }
      throw e;
    }
    for (Entry e : t.entries()) {
      jdbc.sql(
              """
              insert into entries (transaction_id, account_id, direction, amount, currency, balance_after, created_at)
              values (:transactionId, :accountId, :direction, :amount, :currency, :balanceAfter, :createdAt)
              """)
          .param("transactionId", t.id())
          .param("accountId", e.accountId())
          .param("direction", e.direction().name())
          .param("amount", e.amount())
          .param("currency", e.currency())
          .param("balanceAfter", e.balanceAfter())
          .param("createdAt", Sql.ts(t.createdAt()))
          .update();
    }
  }

  @Override
  public Optional<Transaction> find(UUID id) {
    return select(id, "");
  }

  @Override
  public Optional<Transaction> lock(UUID id) {
    // The journal is append-only, so the runtime role has no UPDATE privilege and cannot use
    // SELECT ... FOR UPDATE here. A transaction-scoped advisory lock serializes the same way.
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:id, 0))")
        .param("id", id.toString())
        .query()
        .singleRow();
    return select(id, "");
  }

  private Optional<Transaction> select(UUID id, String lockClause) {
    List<Entry> entries =
        jdbc.sql(
                """
                select account_id, direction, amount, currency, balance_after
                  from entries where transaction_id = :id order by seq
                """)
            .param("id", id)
            .query(
                (rs, n) ->
                    new Entry(
                        rs.getObject("account_id", UUID.class),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getLong("balance_after")))
            .list();
    return jdbc.sql(
            """
            select id, kind, reference, description, reverses_id, hold_id, created_at
              from transactions where id = :id
            """
                + lockClause)
        .param("id", id)
        .query(
            (rs, n) ->
                new Transaction(
                    rs.getObject("id", UUID.class),
                    TransactionKind.valueOf(rs.getString("kind")),
                    rs.getString("reference"),
                    rs.getString("description"),
                    rs.getObject("reverses_id", UUID.class),
                    rs.getObject("hold_id", UUID.class),
                    Sql.instant(rs, "created_at"),
                    entries))
        .optional();
  }

  @Override
  public boolean isReversed(UUID id) {
    return jdbc.sql("select exists (select 1 from transactions where reverses_id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<StatementLine> statement(UUID accountId, StatementQuery q) {
    StringBuilder sql =
        new StringBuilder(
            """
            select e.seq, e.transaction_id, t.kind, t.reference, e.direction, e.amount,
                   e.currency, e.balance_after, e.created_at
              from entries e join transactions t on t.id = e.transaction_id
             where e.account_id = :accountId
            """);
    if (q.afterSeq() != null) {
      sql.append(" and e.seq > :afterSeq");
    }
    if (q.from() != null) {
      sql.append(" and e.created_at >= :from");
    }
    if (q.to() != null) {
      sql.append(" and e.created_at < :to");
    }
    sql.append(" order by e.seq limit :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString()).param("accountId", accountId).param("limit", q.limit() + 1);
    if (q.afterSeq() != null) {
      spec = spec.param("afterSeq", q.afterSeq());
    }
    if (q.from() != null) {
      spec = spec.param("from", Sql.ts(q.from()));
    }
    if (q.to() != null) {
      spec = spec.param("to", Sql.ts(q.to()));
    }
    return spec.query(
            (rs, n) ->
                new StatementLine(
                    rs.getLong("seq"),
                    rs.getObject("transaction_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("reference"),
                    rs.getString("direction"),
                    rs.getLong("amount"),
                    rs.getString("currency"),
                    rs.getLong("balance_after"),
                    Sql.instant(rs, "created_at")))
        .list();
  }
}
