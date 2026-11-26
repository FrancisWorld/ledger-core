package io.github.francisworld.ledger.bootstrap;

import io.github.francisworld.ledger.application.port.in.AccountUseCase;
import io.github.francisworld.ledger.application.port.in.ApiKeyUseCase;
import io.github.francisworld.ledger.application.port.in.HoldUseCase;
import io.github.francisworld.ledger.application.port.in.PostingUseCase;
import io.github.francisworld.ledger.application.port.in.ReportUseCase;
import io.github.francisworld.ledger.application.port.out.AccountRepository;
import io.github.francisworld.ledger.application.port.out.ApiKeyRepository;
import io.github.francisworld.ledger.application.port.out.EventSink;
import io.github.francisworld.ledger.application.port.out.HoldRepository;
import io.github.francisworld.ledger.application.port.out.IdGenerator;
import io.github.francisworld.ledger.application.port.out.IdempotencyStore;
import io.github.francisworld.ledger.application.port.out.LedgerMetrics;
import io.github.francisworld.ledger.application.port.out.OutboxStore;
import io.github.francisworld.ledger.application.port.out.ReportQuery;
import io.github.francisworld.ledger.application.port.out.TransactionRepository;
import io.github.francisworld.ledger.application.port.out.UnitOfWork;
import io.github.francisworld.ledger.application.service.ApiKeyService;
import io.github.francisworld.ledger.application.service.IdempotentExecutor;
import io.github.francisworld.ledger.application.service.LedgerServices;
import io.github.francisworld.ledger.application.service.OutboxRelay;
import io.github.francisworld.ledger.application.service.ReportService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: the application layer is plain Java and is wired to its adapters here. */
@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  LedgerServices ledgerServices(
      AccountRepository accounts,
      TransactionRepository transactions,
      HoldRepository holds,
      OutboxStore outbox,
      UnitOfWork unitOfWork,
      IdGenerator ids,
      Clock clock,
      LedgerMetrics metrics) {
    return LedgerServices.create(
        accounts, transactions, holds, outbox, unitOfWork, ids, clock, metrics);
  }

  @Bean
  AccountUseCase accountUseCase(LedgerServices services) {
    return services.accounts();
  }

  @Bean
  PostingUseCase postingUseCase(LedgerServices services) {
    return services.posting();
  }

  @Bean
  HoldUseCase holdUseCase(LedgerServices services) {
    return services.holds();
  }

  @Bean
  ReportUseCase reportUseCase(ReportQuery query) {
    return new ReportService(query);
  }

  @Bean
  ApiKeyUseCase apiKeyUseCase(ApiKeyRepository repository, IdGenerator ids) {
    return new ApiKeyService(repository, ids);
  }

  @Bean
  IdempotentExecutor idempotentExecutor(
      IdempotencyStore store, UnitOfWork unitOfWork, LedgerMetrics metrics) {
    return new IdempotentExecutor(store, unitOfWork, metrics);
  }

  @Bean
  OutboxRelay outboxRelay(
      OutboxStore store,
      EventSink sink,
      UnitOfWork unitOfWork,
      Clock clock,
      LedgerMetrics metrics,
      LedgerProperties properties) {
    LedgerProperties.Outbox o = properties.outbox();
    return new OutboxRelay(
        store,
        sink,
        unitOfWork,
        clock,
        metrics,
        new OutboxRelay.Settings(o.batchSize(), o.maxAttempts(), o.baseBackoff(), o.maxBackoff()));
  }
}
