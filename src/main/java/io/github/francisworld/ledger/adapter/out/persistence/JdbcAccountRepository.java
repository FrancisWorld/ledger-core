package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.out.AccountRepository;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.AccountType;
import io.github.francisworld.ledger.domain.LedgerException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

  private static final String COLUMNS =
      "id, code, name, type, currency, allow_negative, balance, held, created_at";

  private final JdbcClient jdbc;

  JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Account a) {
    try {
      jdbc.sql(
              """
              insert into accounts (id, code, name, type, currency, allow_negative, balance, held, created_at)
              values (:id, :code, :name, :type, :currency, :allowNegative, :balance, :held, :createdAt)
              """)
          .param("id", a.id())
          .param("code", a.code())
          .param("name", a.name())
          .param("type", a.type().name())
          .param("currency", a.currency())
          .param("allowNegative", a.allowNegative())
          .param("balance", a.balance())
          .param("held", a.held())
          .param("createdAt", Sql.ts(a.createdAt()))
          .update();
    } catch (DuplicateKeyException e) {
      if (Sql.violatedConstraint(e, "accounts_code_key")) {
        throw LedgerException.conflict(
            "duplicate-account-code", "An account with code '" + a.code() + "' already exists");
      }
      throw e;
    }
  }

  @Override
  public Optional<Account> find(UUID id) {
    return select(id, "");
  }

  @Override
  public Optional<Account> lock(UUID id) {
    return select(id, " for update");
  }

  private Optional<Account> select(UUID id, String suffix) {
    return jdbc.sql("select " + COLUMNS + " from accounts where id = :id" + suffix)
        .param("id", id)
        .query(
            (rs, n) ->
                new Account(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    AccountType.valueOf(rs.getString("type")),
                    rs.getString("currency"),
                    rs.getBoolean("allow_negative"),
                    rs.getLong("balance"),
                    rs.getLong("held"),
                    Sql.instant(rs, "created_at")))
        .optional();
  }

  @Override
  public void updateBalances(Collection<Account> accounts) {
    for (Account a : accounts) {
      jdbc.sql("update accounts set balance = :balance, held = :held where id = :id")
          .param("balance", a.balance())
          .param("held", a.held())
          .param("id", a.id())
          .update();
    }
  }
}
