package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.francisworld.ledger.support.AbstractIT;
import io.github.francisworld.ledger.support.PostgresTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The service connects as the {@code ledger_app} role. A compromised service must not be able to
 * rewrite history or switch the integrity triggers off, so those operations are checked here with a
 * raw connection as that role.
 */
class RuntimeRoleIT extends AbstractIT {

  private JdbcClient runtime;
  private UUID transactionId;

  @BeforeEach
  void aTransactionExists() {
    runtime = JdbcClient.create(PostgresTestSupport.runtimeDataSource());
    UUID cash = api.cash(uniqueCode("cash"));
    UUID wallet = api.wallet(uniqueCode("wallet"));
    api.deposit(cash, wallet, 500);
    transactionId =
        runtime
            .sql("select id from transactions order by created_at desc limit 1")
            .query(UUID.class)
            .single();
  }

  @Test
  void theRoleCanDoTheDmlTheServiceNeeds() {
    assertThat(runtime.sql("select count(*) from entries").query(Long.class).single()).isPositive();
    assertThat(runtime.sql("update accounts set held = held where false").update()).isZero();
  }

  @Test
  void journalRowsCannotBeUpdatedDeletedOrTruncated() {
    assertDenied("update entries set amount = 1");
    assertDenied("update transactions set description = 'x'");
    assertDenied("delete from entries");
    assertDenied("delete from transactions");
    assertDenied("truncate entries");
    assertDenied("truncate transactions cascade");
    assertThat(
            PostgresTestSupport.ownerJdbc()
                .sql("select count(*) from entries where transaction_id = :id")
                .param("id", transactionId)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void theIntegrityTriggersCannotBeDisabledOrDropped() {
    assertDenied("alter table entries disable trigger all");
    assertDenied("alter table entries disable trigger entries_append_only");
    assertDenied("drop trigger entries_append_only on entries");
    assertDenied("drop function ledger_reject_mutation() cascade");
    assertDenied("set session_replication_role = replica");
  }

  @Test
  void noDdlAndNoKeyIssuing() {
    assertDenied("create table evil (id int)");
    assertDenied("alter table accounts drop constraint accounts_no_overdraft");
    assertDenied("drop table entries");
    assertDenied(
        "insert into api_keys (id, name, key_hash, scopes) values (gen_random_uuid(), 'x', 'y', '{accounts:read}')");
    assertDenied("create role intruder superuser");
    // a non-owner GRANT is a silent no-op in PostgreSQL, so verify the privileges themselves
    runtime.sql("grant all on entries to ledger_app").update();
    for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE", "TRIGGER"}) {
      assertThat(
              runtime
                  .sql("select has_table_privilege('ledger_app', 'entries', :p)")
                  .param("p", privilege)
                  .query(Boolean.class)
                  .single())
          .as(privilege)
          .isFalse();
    }
  }

  private void assertDenied(String sql) {
    assertThatThrownBy(() -> runtime.sql(sql).update())
        .as(sql)
        .satisfies(
            e ->
                assertThat(String.valueOf(e.getCause()) + e.getMessage())
                    .containsAnyOf("permission denied", "must be owner"));
  }
}
