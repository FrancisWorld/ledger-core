package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.out.IdempotencyStore;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

  private final JdbcClient jdbc;

  JdbcIdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean begin(String key, String fingerprint) {
    // A concurrent insert of the same key blocks on the unique index until that transaction ends.
    return jdbc.sql(
                """
                insert into idempotency_keys (key, fingerprint) values (:key, :fingerprint)
                on conflict (key) do nothing
                """)
            .param("key", key)
            .param("fingerprint", fingerprint)
            .update()
        == 1;
  }

  @Override
  public Optional<Record> find(String key) {
    return jdbc.sql(
            """
            select key, fingerprint, response_status, response_body
              from idempotency_keys where key = :key
            """)
        .param("key", key)
        .query(
            (rs, n) ->
                new Record(
                    rs.getString("key"),
                    rs.getString("fingerprint"),
                    rs.getInt("response_status"),
                    rs.getString("response_body")))
        .optional();
  }

  @Override
  public void complete(String key, int status, String body) {
    jdbc.sql(
            "update idempotency_keys set response_status = :status, response_body = :body where key = :key")
        .param("status", status)
        .param("body", body)
        .param("key", key)
        .update();
  }
}
