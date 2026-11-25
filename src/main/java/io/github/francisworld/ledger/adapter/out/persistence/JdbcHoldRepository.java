package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.out.HoldRepository;
import io.github.francisworld.ledger.domain.Hold;
import io.github.francisworld.ledger.domain.HoldStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHoldRepository implements HoldRepository {

  private static final String COLUMNS =
      """
      id, account_id, amount, currency, status, reference, expires_at, captured_amount,
      capture_transaction_id, created_at, updated_at
      """;

  private final JdbcClient jdbc;

  JdbcHoldRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Hold h) {
    jdbc.sql(
            """
            insert into holds (id, account_id, amount, currency, status, reference, expires_at,
                               captured_amount, capture_transaction_id, created_at, updated_at)
            values (:id, :accountId, :amount, :currency, :status, :reference, :expiresAt,
                    :capturedAmount, :captureTx, :createdAt, :updatedAt)
            """)
        .params(
            java.util.Map.of(
                "id", h.id(),
                "accountId", h.accountId(),
                "amount", h.amount(),
                "currency", h.currency(),
                "status", h.status().name(),
                "createdAt", Sql.ts(h.createdAt()),
                "updatedAt", Sql.ts(h.updatedAt())))
        .param("reference", h.reference())
        .param("expiresAt", Sql.ts(h.expiresAt()))
        .param("capturedAmount", h.capturedAmount())
        .param("captureTx", h.captureTransactionId())
        .update();
  }

  @Override
  public Optional<Hold> find(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from holds where id = :id")
        .param("id", id)
        .query((rs, n) -> map(rs))
        .optional();
  }

  @Override
  public Optional<Hold> lock(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from holds where id = :id for update")
        .param("id", id)
        .query((rs, n) -> map(rs))
        .optional();
  }

  @Override
  public void update(Hold h) {
    jdbc.sql(
            """
            update holds set status = :status, captured_amount = :capturedAmount,
                   capture_transaction_id = :captureTx, updated_at = :updatedAt
             where id = :id
            """)
        .param("status", h.status().name())
        .param("capturedAmount", h.capturedAmount())
        .param("captureTx", h.captureTransactionId())
        .param("updatedAt", Sql.ts(h.updatedAt()))
        .param("id", h.id())
        .update();
  }

  @Override
  public List<Hold> lockExpired(Instant now, int limit) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + """
                   from holds
                  where status = 'ACTIVE' and expires_at is not null and expires_at <= :now
                  order by expires_at
                  limit :limit
                  for update skip locked
                  """)
        .param("now", Sql.ts(now))
        .param("limit", limit)
        .query((rs, n) -> map(rs))
        .list();
  }

  private static Hold map(ResultSet rs) throws SQLException {
    return new Hold(
        rs.getObject("id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getLong("amount"),
        rs.getString("currency"),
        HoldStatus.valueOf(rs.getString("status")),
        rs.getString("reference"),
        Sql.instant(rs, "expires_at"),
        Sql.nullableLong(rs, "captured_amount"),
        rs.getObject("capture_transaction_id", UUID.class),
        Sql.instant(rs, "created_at"),
        Sql.instant(rs, "updated_at"));
  }
}
