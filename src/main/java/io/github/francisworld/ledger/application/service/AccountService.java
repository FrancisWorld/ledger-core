package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.in.AccountUseCase;
import io.github.francisworld.ledger.application.port.out.AccountRepository;
import io.github.francisworld.ledger.application.port.out.IdGenerator;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.OutboxStore.NewEvent;
import io.github.francisworld.ledger.application.port.out.TransactionRepository;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.Currencies;
import io.github.francisworld.ledger.domain.LedgerException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AccountService implements AccountUseCase {

  public static final int MAX_STATEMENT_LIMIT = 500;

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final OutboxStore outbox;
  private final UnitOfWork unitOfWork;
  private final IdGenerator ids;
  private final Clock clock;

  public AccountService(
      AccountRepository accounts,
      TransactionRepository transactions,
      OutboxStore outbox,
      UnitOfWork unitOfWork,
      IdGenerator ids,
      Clock clock) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.outbox = outbox;
    this.unitOfWork = unitOfWork;
    this.ids = ids;
    this.clock = clock;
  }

  @Override
  public Account open(OpenAccount command) {
    Currencies.require(command.currency());
    Account account =
        Account.open(
            ids.next(),
            command.code(),
            command.name(),
            command.type(),
            command.currency(),
            command.allowNegative(),
            clock.instant());
    return unitOfWork.execute(
        () -> {
          accounts.insert(account);
          outbox.append(
              new NewEvent(
                  "account",
                  account.id().toString(),
                  "account.opened",
                  Map.of(
                      "account_id", account.id().toString(),
                      "code", account.code(),
                      "type", account.type().name(),
                      "currency", account.currency())));
          return account;
        });
  }

  @Override
  public Account get(UUID id) {
    return accounts.find(id).orElseThrow(() -> notFound(id));
  }

  @Override
  public Statement statement(UUID accountId, StatementQuery query) {
    if (query.limit() < 1 || query.limit() > MAX_STATEMENT_LIMIT) {
      throw LedgerException.invalid(
          "invalid-limit", "limit must be between 1 and " + MAX_STATEMENT_LIMIT);
    }
    if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
      throw LedgerException.invalid("invalid-range", "from must be before to");
    }
    return unitOfWork.execute(
        () -> {
          Account account = get(accountId);
          List<StatementLine> rows = transactions.statement(accountId, query);
          boolean more = rows.size() > query.limit();
          List<StatementLine> page = more ? rows.subList(0, query.limit()) : rows;
          Long next = more ? page.get(page.size() - 1).seq() : null;
          return new Statement(account, List.copyOf(page), next);
        });
  }

  private static LedgerException notFound(UUID id) {
    return LedgerException.notFound("account-not-found", "Account " + id + " does not exist");
  }
}
