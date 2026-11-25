package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit of work backed by a Spring-managed JDBC transaction (READ COMMITTED + explicit row locks).
 */
@Component
class TransactionalUnitOfWork implements UnitOfWork {

  private final TransactionTemplate template;

  TransactionalUnitOfWork(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
  }

  @Override
  public <T> T execute(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
