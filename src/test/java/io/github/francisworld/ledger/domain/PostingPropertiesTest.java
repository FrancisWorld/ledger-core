package io.github.francisworld.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based checks of the posting engine over random worlds: a cash account (debit-normal, may
 * go negative) backing several customer wallets (credit-normal, never overdrawn).
 */
class PostingPropertiesTest {

  private static final int WALLETS = 5;

  /**
   * A world is a list of starting wallet balances; cash starts at their sum so the books balance.
   */
  record World(List<Account> accounts) {

    Map<UUID, Account> byId() {
      Map<UUID, Account> map = new HashMap<>();
      accounts.forEach(a -> map.put(a.id(), a));
      return map;
    }
  }

  record Move(int from, int to, long amount) {}

  @Provide
  Arbitrary<World> worlds() {
    return Arbitraries.longs()
        .between(0, 10_000)
        .list()
        .ofSize(WALLETS)
        .map(
            balances -> {
              List<Account> accounts = new ArrayList<>();
              accounts.add(TestAccounts.cash(balances.stream().mapToLong(Long::longValue).sum()));
              balances.forEach(b -> accounts.add(TestAccounts.wallet(b)));
              return new World(accounts);
            });
  }

  @Provide
  Arbitrary<List<Move>> journals() {
    Arbitrary<Move> move =
        Arbitraries.integers()
            .between(0, WALLETS)
            .flatMap(
                from ->
                    Arbitraries.integers()
                        .between(0, WALLETS)
                        .filter(to -> to != from)
                        .flatMap(
                            to ->
                                Arbitraries.longs()
                                    .between(1, 12_000)
                                    .map(amount -> new Move(from, to, amount))));
    return move.list().ofMinSize(1).ofMaxSize(6);
  }

  private static List<EntryDraft> drafts(World world, List<Move> moves) {
    List<EntryDraft> drafts = new ArrayList<>();
    for (Move m : moves) {
      drafts.add(new EntryDraft(world.accounts().get(m.from()).id(), Direction.DEBIT, m.amount()));
      drafts.add(new EntryDraft(world.accounts().get(m.to()).id(), Direction.CREDIT, m.amount()));
    }
    return drafts;
  }

  /** Accounting equation: debit-normal balances equal credit-normal balances. */
  private static long skew(Iterable<Account> accounts) {
    long skew = 0;
    for (Account a : accounts) {
      skew += a.normalSide() == Direction.DEBIT ? a.balance() : -a.balance();
    }
    return skew;
  }

  private static Posting.Result tryApply(World world, List<EntryDraft> drafts) {
    try {
      return Posting.apply(world.byId(), drafts);
    } catch (LedgerException e) {
      assertThat(e.code()).isEqualTo("insufficient-funds");
      return null;
    }
  }

  @Property
  void everyAcceptedJournalPreservesTheAccountingEquation(
      @ForAll("worlds") World world, @ForAll("journals") List<Move> moves) {
    Posting.Result result = tryApply(world, drafts(world, moves));

    assertThat(skew(world.accounts())).isZero();
    if (result != null) {
      assertThat(skew(result.accounts().values())).isZero();
    }
  }

  @Property
  void acceptedJournalsNeverLeaveAWalletOverdrawn(
      @ForAll("worlds") World world, @ForAll("journals") List<Move> moves) {
    Posting.Result result = tryApply(world, drafts(world, moves));

    if (result != null) {
      result.accounts().values().stream()
          .filter(a -> !a.allowNegative())
          .forEach(a -> assertThat(a.available()).isGreaterThanOrEqualTo(0));
    }
  }

  @Property
  void lineOrderDoesNotChangeTheOutcome(
      @ForAll("worlds") World world, @ForAll("journals") List<Move> moves, @ForAll long seed) {
    List<EntryDraft> original = drafts(world, moves);
    List<EntryDraft> shuffled = new ArrayList<>(original);
    Collections.shuffle(shuffled, new java.util.Random(seed));

    Posting.Result a = tryApply(world, original);
    Posting.Result b = tryApply(world, shuffled);

    assertThat(a == null).isEqualTo(b == null);
    if (a != null) {
      a.accounts()
          .forEach(
              (id, account) ->
                  assertThat(b.accounts().get(id).balance()).isEqualTo(account.balance()));
    }
  }

  @Property
  void aReversalRestoresEveryBalance(
      @ForAll("worlds") World world, @ForAll("journals") List<Move> moves) {
    Posting.Result forward = tryApply(world, drafts(world, moves));
    if (forward == null) {
      return;
    }

    Posting.Result back = Posting.apply(forward.accounts(), Posting.inverse(forward.entries()));

    world
        .accounts()
        .forEach(a -> assertThat(back.accounts().get(a.id()).balance()).isEqualTo(a.balance()));
  }

  @Property
  void runningBalanceOfTheLastEntryMatchesTheFinalAccountState(
      @ForAll("worlds") World world, @ForAll("journals") List<Move> moves) {
    Posting.Result result = tryApply(world, drafts(world, moves));
    if (result == null) {
      return;
    }

    Map<UUID, Long> lastSeen = new HashMap<>();
    result.entries().forEach(e -> lastSeen.put(e.accountId(), e.balanceAfter()));

    lastSeen.forEach(
        (id, balance) -> assertThat(result.accounts().get(id).balance()).isEqualTo(balance));
  }
}
