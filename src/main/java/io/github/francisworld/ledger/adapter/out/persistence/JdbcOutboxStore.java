package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.out.OutboxStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class JdbcOutboxStore implements OutboxStore {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  JdbcOutboxStore(JdbcClient jdbc, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public void append(NewEvent event) {
    Instant now = clock.instant();
    jdbc.sql(
            """
            insert into outbox (aggregate_type, aggregate_id, event_type, payload, next_attempt_at, created_at)
            values (:aggregateType, :aggregateId, :eventType, cast(:payload as jsonb), :now, :now)
            """)
        .param("aggregateType", event.aggregateType())
        .param("aggregateId", event.aggregateId())
        .param("eventType", event.eventType())
        .param("payload", json.writeValueAsString(event.payload()))
        .param("now", Sql.ts(now))
        .update();
  }

  @Override
  public List<Message> claim(Instant now, int limit) {
    return jdbc.sql(
            """
            select id, aggregate_type, aggregate_id, event_type, payload::text as payload, created_at, attempts
              from outbox
             where status = 'PENDING' and next_attempt_at <= :now
             order by id
             limit :limit
             for update skip locked
            """)
        .param("now", Sql.ts(now))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Message(
                    rs.getLong("id"),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    Sql.instant(rs, "created_at"),
                    rs.getInt("attempts")))
        .list();
  }

  @Override
  public void markPublished(Collection<Long> ids, Instant now) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            update outbox set status = 'PUBLISHED', published_at = :now, attempts = attempts + 1
             where id in (:ids)
            """)
        .param("now", Sql.ts(now))
        .param("ids", ids)
        .update();
  }

  @Override
  public void markFailed(long id, int attempts, Instant nextAttemptAt, String error) {
    jdbc.sql(
            """
            update outbox
               set attempts = :attempts,
                   last_error = :error,
                   status = case when cast(:next as timestamptz) is null then 'DEAD' else 'PENDING' end,
                   next_attempt_at = coalesce(cast(:next as timestamptz), next_attempt_at)
             where id = :id
            """)
        .param("attempts", attempts)
        .param("error", error)
        .param("next", Sql.ts(nextAttemptAt))
        .param("id", id)
        .update();
  }
}
