package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.francisworld.ledger.support.AbstractIT;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Talks to PostgreSQL directly, bypassing the application, to prove the invariants hold for any
 * writer and not just for this service's code paths.
 */
class DatabaseInvariantsIT extends AbstractIT {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private UUID cash;
  private UUID wallet;

  @BeforeEach
  void accounts() {
    cash = api.cash(uniqueCode("cash"));
    wallet = api.wallet(uniqueCode("wallet"));
  }

  private void transaction(UUID id, int entryCount) {
    jdbc.sql(
            "insert into transactions (id, kind, entry_count, created_at) values (:id, 'JOURNAL', :n, :now)")
        .param("id", id)
        .param("n", entryCount)
        .param("now", OffsetDateTime.now(ZoneOffset.UTC))
        .update();
  }

  private void entry(
      UUID tx, UUID account, String direction, long amount, String currency, long balanceAfter) {
    jdbc.sql(
            """
            insert into entries (transaction_id, account_id, direction, amount, currency, balance_after, created_at)
            values (:tx, :account, :direction, :amount, :currency, :balanceAfter, :now)
            """)
        .param("tx", tx)
        .param("account", account)
        .param("direction", direction)
        .param("amount", amount)
        .param("currency", currency)
        .param("balanceAfter", balanceAfter)
        .param("now", OffsetDateTime.now(ZoneOffset.UTC))
        .update();
  }

  private void setBalance(UUID account, long balance) {
    jdbc.sql("update accounts set balance = :b where id = :id")
        .param("b", balance)
        .param("id", account)
        .update();
  }

  private void postBalanced(UUID id, long amount) {
    tx.executeWithoutResult(
        s -> {
          transaction(id, 2);
          entry(id, cash, "DEBIT", amount, "USD", amount);
          entry(id, wallet, "CREDIT", amount, "USD", amount);
          setBalance(cash, amount);
          setBalance(wallet, amount);
        });
  }

  @Test
  void aBalancedTransactionCommits() {
    UUID id = UUID.randomUUID();

    postBalanced(id, 700);

    assertThat(
            jdbc.sql("select count(*) from entries where transaction_id = :id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void unbalancedEntriesAreRejectedAtCommit() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      transaction(id, 2);
                      entry(id, cash, "DEBIT", 100, "USD", 100);
                      entry(id, wallet, "CREDIT", 90, "USD", 90);
                    }))
        .hasStackTraceContaining("is unbalanced by");
    assertThat(
            jdbc.sql("select count(*) from transactions where id = :id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void aTransactionMustHaveExactlyTheEntriesItDeclares() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      transaction(id, 3);
                      entry(id, cash, "DEBIT", 100, "USD", 100);
                      entry(id, wallet, "CREDIT", 100, "USD", 100);
                    }))
        .hasStackTraceContaining("declares 3 entries but has 2");
  }

  @Test
  void entriesCannotBeAppendedToAnOldTransaction() {
    UUID id = UUID.randomUUID();
    postBalanced(id, 100);

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      entry(id, cash, "DEBIT", 5, "USD", 105);
                      entry(id, wallet, "CREDIT", 5, "USD", 105);
                    }))
        .hasStackTraceContaining("declares 2 entries but has 4");
  }

  @Test
  void theJournalIsAppendOnly() {
    UUID id = UUID.randomUUID();
    postBalanced(id, 100);

    assertThatThrownBy(
            () ->
                jdbc.sql("update entries set amount = 1 where transaction_id = :id")
                    .param("id", id)
                    .update())
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.sql("delete from entries where transaction_id = :id").param("id", id).update())
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.sql("update transactions set description = 'x' where id = :id")
                    .param("id", id)
                    .update())
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.sql("delete from transactions where id = :id").param("id", id).update())
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("truncate entries").update())
        .hasStackTraceContaining("append-only");
  }

  @Test
  void aNonOverdraftableAccountCannotGoNegativeOrHoldMoreThanItHas() {
    assertThatThrownBy(() -> setBalance(wallet, -1))
        .hasStackTraceContaining("accounts_no_overdraft");
    assertThatThrownBy(
            () ->
                jdbc.sql("update accounts set held = 5 where id = :id")
                    .param("id", wallet)
                    .update())
        .hasStackTraceContaining("accounts_no_overdraft");
    assertThatThrownBy(
            () ->
                jdbc.sql("update accounts set held = -1 where id = :id")
                    .param("id", wallet)
                    .update())
        .hasStackTraceContaining("accounts_held_non_negative");

    // allowNegative accounts are exempt (rolled back so the books stay consistent for other tests)
    tx.executeWithoutResult(
        s -> {
          setBalance(cash, -1_000);
          s.setRollbackOnly();
        });
  }

  @Test
  void anEntryMustUseTheCurrencyOfItsAccount() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      transaction(id, 2);
                      entry(id, cash, "DEBIT", 10, "EUR", 10);
                      entry(id, wallet, "CREDIT", 10, "EUR", 10);
                    }))
        .hasStackTraceContaining("entries_account_currency_fk");
  }
}
