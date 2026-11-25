package io.github.francisworld.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.francisworld.ledger.application.port.in.AccountUseCase;
import io.github.francisworld.ledger.application.port.in.AccountUseCase.OpenAccount;
import io.github.francisworld.ledger.application.port.in.HoldUseCase;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.CaptureHold;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.PlaceHold;
import io.github.francisworld.ledger.application.port.in.PostingUseCase;
import io.github.francisworld.ledger.application.port.in.PostingUseCase.Journal;
import io.github.francisworld.ledger.application.port.in.PostingUseCase.JournalLine;
import io.github.francisworld.ledger.application.port.in.PostingUseCase.Transfer;
import io.github.francisworld.ledger.application.port.in.ReportUseCase;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.AccountType;
import io.github.francisworld.ledger.domain.Direction;
import io.github.francisworld.ledger.domain.Hold;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Transaction;
import io.github.francisworld.ledger.support.AbstractIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Hammers the service layer from many threads against real PostgreSQL and then audits the books.
 * These are the tests that would catch lost updates, deadlocks and overdrafts.
 */
class ConcurrencyIT extends AbstractIT {

  private static final Set<String> EXPECTED_REJECTIONS =
      Set.of("insufficient-funds", "hold-not-active", "already-reversed");

  @Autowired AccountUseCase accounts;
  @Autowired PostingUseCase posting;
  @Autowired HoldUseCase holds;
  @Autowired ReportUseCase reports;
  @Autowired JdbcClient jdbc;

  private Account open(AccountType type, boolean allowNegative) {
    return accounts.open(new OpenAccount(uniqueCode("acct"), "n", type, "USD", allowNegative));
  }

  private void deposit(Account cash, Account wallet, long amount) {
    posting.post(
        new Journal(
            List.of(
                new JournalLine(cash.id(), Direction.DEBIT, amount),
                new JournalLine(wallet.id(), Direction.CREDIT, amount)),
            "seed",
            null));
  }

  private static void rethrowUnexpected(LedgerException e) {
    if (!EXPECTED_REJECTIONS.contains(e.code())) {
      throw e;
    }
  }

  @Test
  void randomMixedWorkloadConservesMoneyAndKeepsEveryInvariant() throws Exception {
    int wallets = 12;
    long seed = 5_000;
    int threads = 24;
    int opsPerThread = 120;
    Account cash = open(AccountType.ASSET, true);
    List<Account> pool = new ArrayList<>();
    for (int i = 0; i < wallets; i++) {
      Account w = open(AccountType.LIABILITY, false);
      deposit(cash, w, seed);
      pool.add(w);
    }
    Queue<UUID> activeHolds = new ConcurrentLinkedQueue<>();
    Queue<UUID> postedTransactions = new ConcurrentLinkedQueue<>();
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();

    long startedAt = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch go = new CountDownLatch(1);
      for (int t = 0; t < threads; t++) {
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  ThreadLocalRandom rnd = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    Account a = pool.get(rnd.nextInt(wallets));
                    Account b = pool.get(rnd.nextInt(wallets));
                    int roll = rnd.nextInt(100);
                    try {
                      if (roll < 60 && a != b) {
                        Transaction tx =
                            posting.transfer(
                                new Transfer(
                                    a.id(), b.id(), rnd.nextLong(1, 1_500), "USD", null, null));
                        postedTransactions.add(tx.id());
                      } else if (roll < 75) {
                        Hold h =
                            holds.place(
                                new PlaceHold(a.id(), rnd.nextLong(1, 800), "USD", null, null));
                        activeHolds.add(h.id());
                      } else if (roll < 85 && a != b) {
                        UUID holdId = activeHolds.poll();
                        if (holdId != null) {
                          Hold h = holds.get(holdId);
                          UUID destination = h.accountId().equals(b.id()) ? a.id() : b.id();
                          holds.capture(
                              new CaptureHold(
                                  holdId, destination, rnd.nextLong(1, h.amount() + 1), null));
                        }
                      } else if (roll < 90) {
                        UUID holdId = activeHolds.poll();
                        if (holdId != null) {
                          holds.release(holdId);
                        }
                      } else {
                        UUID target = postedTransactions.poll();
                        if (target != null) {
                          posting.reverse(target, "load test");
                        }
                      }
                      succeeded.incrementAndGet();
                    } catch (LedgerException e) {
                      rethrowUnexpected(e);
                      rejected.incrementAndGet();
                    }
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
    System.out.printf(
        "[ConcurrencyIT] %d threads x %d mixed ops: %d succeeded, %d rejected in %d ms%n",
        threads, opsPerThread, succeeded.get(), rejected.get(), elapsed.toMillis());

    // 1. conservation: wallets only ever trade with each other, so their sum is the seed total
    List<Account> after = pool.stream().map(w -> accounts.get(w.id())).toList();
    assertThat(after.stream().mapToLong(Account::balance).sum()).isEqualTo(wallets * seed);
    assertThat(accounts.get(cash.id()).balance()).isEqualTo(wallets * seed);

    // 2. no overdraft, and held funds equal the sum of the still-active holds
    for (Account w : after) {
      long activeHeld =
          jdbc.sql(
                  "select coalesce(sum(amount), 0) from holds where account_id = :id and status = 'ACTIVE'")
              .param("id", w.id())
              .query(Long.class)
              .single();
      assertThat(w.balance()).isGreaterThanOrEqualTo(0);
      assertThat(w.available()).isGreaterThanOrEqualTo(0);
      assertThat(w.held()).isEqualTo(activeHeld);
    }

    // 3. every account's running balance chain is unbroken and ends at the stored balance
    long brokenChains =
        jdbc.sql(
                """
                select count(*) from (
                  select e.account_id, e.balance_after,
                         lag(e.balance_after, 1, 0::bigint) over (partition by e.account_id order by e.seq) as previous,
                         case when e.direction = case when a.type in ('ASSET', 'EXPENSE') then 'DEBIT' else 'CREDIT' end
                              then e.amount else -e.amount end as signed
                    from entries e join accounts a on a.id = e.account_id
                   where e.account_id = any (:ids)
                ) chain
                where balance_after <> previous + signed
                """)
            .param("ids", pool.stream().map(Account::id).toList().toArray(new UUID[0]))
            .query(Long.class)
            .single();
    assertThat(brokenChains).isZero();

    // 4. the ledger-wide trial balance still balances with no drifted account
    assertThat(reports.trialBalance().balanced()).isTrue();
  }

  @Test
  void opposingTransfersBetweenTheSamePairNeverDeadlock() throws Exception {
    Account cash = open(AccountType.ASSET, true);
    Account a = open(AccountType.LIABILITY, false);
    Account b = open(AccountType.LIABILITY, false);
    deposit(cash, a, 1_000_000);
    deposit(cash, b, 1_000_000);
    int perDirection = 150;

    try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 16; t++) {
        boolean forward = t % 2 == 0;
        futures.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < perDirection; i++) {
                    Account from = forward ? a : b;
                    Account to = forward ? b : a;
                    posting.transfer(new Transfer(from.id(), to.id(), 1, "USD", null, null));
                  }
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get(); // a deadlock victim would surface here as an ExecutionException
      }
    }

    assertThat(accounts.get(a.id()).balance() + accounts.get(b.id()).balance())
        .isEqualTo(2_000_000);
    assertThat(accounts.get(a.id()).balance()).isEqualTo(1_000_000);
  }

  @Test
  void contendedSpendingNeverOverdrawsAnAccount() throws Exception {
    Account cash = open(AccountType.ASSET, true);
    Account source = open(AccountType.LIABILITY, false);
    Account sink = open(AccountType.LIABILITY, false);
    deposit(cash, source, 1_000);
    int contenders = 64;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger insufficient = new AtomicInteger();

    try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < contenders; i++) {
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  try {
                    posting.transfer(new Transfer(source.id(), sink.id(), 100, "USD", null, null));
                    ok.incrementAndGet();
                  } catch (LedgerException e) {
                    assertThat(e.code()).isEqualTo("insufficient-funds");
                    insufficient.incrementAndGet();
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(ok.get()).isEqualTo(10);
    assertThat(insufficient.get()).isEqualTo(contenders - 10);
    assertThat(accounts.get(source.id()).balance()).isZero();
    assertThat(accounts.get(sink.id()).balance()).isEqualTo(1_000);
  }

  @Test
  void contendedHoldsCannotReserveMoreThanTheBalance() throws Exception {
    Account cash = open(AccountType.ASSET, true);
    Account wallet = open(AccountType.LIABILITY, false);
    deposit(cash, wallet, 1_000);
    int contenders = 40;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();

    try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < contenders; i++) {
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  try {
                    holds.place(new PlaceHold(wallet.id(), 100, "USD", null, null));
                    ok.incrementAndGet();
                  } catch (LedgerException e) {
                    assertThat(e.code()).isEqualTo("insufficient-funds");
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(ok.get()).isEqualTo(10);
    Account after = accounts.get(wallet.id());
    assertThat(after.held()).isEqualTo(1_000);
    assertThat(after.available()).isZero();
  }

  @Test
  void racingReversalsOfOneTransactionSucceedExactlyOnce() throws Exception {
    Account cash = open(AccountType.ASSET, true);
    Account a = open(AccountType.LIABILITY, false);
    Account b = open(AccountType.LIABILITY, false);
    deposit(cash, a, 1_000);
    Transaction original = posting.transfer(new Transfer(a.id(), b.id(), 400, "USD", null, null));
    int racers = 12;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger reversed = new AtomicInteger();

    try (ExecutorService executor = Executors.newFixedThreadPool(racers)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  try {
                    posting.reverse(original.id(), "race");
                    reversed.incrementAndGet();
                  } catch (LedgerException e) {
                    assertThat(e.code()).isEqualTo("already-reversed");
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(reversed.get()).isEqualTo(1);
    assertThat(accounts.get(a.id()).balance()).isEqualTo(1_000);
    assertThat(accounts.get(b.id()).balance()).isZero();
  }
}
