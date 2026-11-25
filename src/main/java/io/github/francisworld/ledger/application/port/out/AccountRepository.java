package io.github.francisworld.ledger.application.port.out;

import io.github.francisworld.ledger.domain.Account;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

  /** Fails with a conflict when the account code is already taken. */
  void insert(Account account);

  Optional<Account> find(UUID id);

  /** Reads the account and takes a row lock held until the surrounding unit of work ends. */
  Optional<Account> lock(UUID id);

  void updateBalances(Collection<Account> accounts);
}
