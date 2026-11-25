package io.github.francisworld.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {

  private static Map<UUID, Account> byId(Account... accounts) {
    return java.util.Arrays.stream(accounts)
        .collect(java.util.stream.Collectors.toMap(Account::id, a -> a));
  }

  private static String codeOf(Throwable t) {
    return ((LedgerException) t).code();
  }

  @Test
  void depositDebitsCashAndCreditsTheWallet() {
    Account cash = TestAccounts.cash(0);
    Account wallet = TestAccounts.wallet(0);

    Posting.Result result =
        Posting.apply(
            byId(cash, wallet),
            List.of(
                new EntryDraft(cash.id(), Direction.DEBIT, 1_000),
                new EntryDraft(wallet.id(), Direction.CREDIT, 1_000)));

    assertThat(result.accounts().get(cash.id()).balance()).isEqualTo(1_000);
    assertThat(result.accounts().get(wallet.id()).balance()).isEqualTo(1_000);
    assertThat(result.entries()).extracting(Entry::balanceAfter).containsExactly(1_000L, 1_000L);
  }

  @Test
  void rejectsJournalsWhereDebitsAndCreditsDiffer() {
    Account cash = TestAccounts.cash(0);
    Account wallet = TestAccounts.wallet(0);

    assertThatThrownBy(
            () ->
                Posting.apply(
                    byId(cash, wallet),
                    List.of(
                        new EntryDraft(cash.id(), Direction.DEBIT, 1_000),
                        new EntryDraft(wallet.id(), Direction.CREDIT, 999))))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("unbalanced-transaction"));
  }

  @Test
  void balanceIsRequiredPerCurrencyNotInTotal() {
    Account usdCash = TestAccounts.cash(0);
    Account eurWallet = TestAccounts.of(AccountType.LIABILITY, "EUR", false, 0);

    assertThatThrownBy(
            () ->
                Posting.apply(
                    byId(usdCash, eurWallet),
                    List.of(
                        new EntryDraft(usdCash.id(), Direction.DEBIT, 100),
                        new EntryDraft(eurWallet.id(), Direction.CREDIT, 100))))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("unbalanced-transaction"));
  }

  @Test
  void rejectsSingleEntryAndOversizedJournals() {
    Account cash = TestAccounts.cash(0);

    assertThatThrownBy(
            () -> Posting.apply(byId(cash), List.of(new EntryDraft(cash.id(), Direction.DEBIT, 1))))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("too-few-entries"));

    List<EntryDraft> tooMany =
        java.util.stream.IntStream.range(0, Posting.MAX_ENTRIES + 1)
            .mapToObj(
                i -> new EntryDraft(cash.id(), i % 2 == 0 ? Direction.DEBIT : Direction.CREDIT, 1))
            .toList();
    assertThatThrownBy(() -> Posting.apply(byId(cash), tooMany))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("too-many-entries"));
  }

  @Test
  void rejectsNonPositiveAndAbsurdAmounts() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> new EntryDraft(id, Direction.DEBIT, 0))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-amount"));
    assertThatThrownBy(() -> new EntryDraft(id, Direction.DEBIT, -5))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-amount"));

    Account cash = TestAccounts.cash(0);
    Account wallet = TestAccounts.wallet(0);
    long huge = Posting.MAX_AMOUNT + 1;
    assertThatThrownBy(
            () ->
                Posting.apply(
                    byId(cash, wallet),
                    List.of(
                        new EntryDraft(cash.id(), Direction.DEBIT, huge),
                        new EntryDraft(wallet.id(), Direction.CREDIT, huge))))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("invalid-amount"));
  }

  @Test
  void unknownAccountsAreReported() {
    Account cash = TestAccounts.cash(0);

    assertThatThrownBy(
            () ->
                Posting.apply(
                    byId(cash),
                    List.of(
                        new EntryDraft(cash.id(), Direction.DEBIT, 1),
                        new EntryDraft(UUID.randomUUID(), Direction.CREDIT, 1))))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("account-not-found"));
  }

  @Test
  void overdraftIsJudgedOnTheFinalStateSoLineOrderDoesNotMatter() {
    Account wallet = TestAccounts.wallet(0);
    Account cash = TestAccounts.cash(0);
    Account other = TestAccounts.wallet(0);
    // Debit first would dip below zero mid-journal; the journal as a whole is fine.
    List<EntryDraft> drafts =
        List.of(
            new EntryDraft(wallet.id(), Direction.DEBIT, 50),
            new EntryDraft(other.id(), Direction.CREDIT, 50),
            new EntryDraft(wallet.id(), Direction.CREDIT, 80),
            new EntryDraft(cash.id(), Direction.DEBIT, 80));

    Posting.Result result = Posting.apply(byId(wallet, cash, other), drafts);

    assertThat(result.accounts().get(wallet.id()).balance()).isEqualTo(30);
  }

  @Test
  void aJournalThatLeavesAnAccountShortIsRejected() {
    Account wallet = TestAccounts.wallet(10);
    Account other = TestAccounts.wallet(0);

    assertThatThrownBy(
            () ->
                Posting.apply(
                    byId(wallet, other),
                    List.of(
                        new EntryDraft(wallet.id(), Direction.DEBIT, 11),
                        new EntryDraft(other.id(), Direction.CREDIT, 11))))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("insufficient-funds"));
  }

  @Test
  void lockOrderIsSortedAndDeduplicated() {
    UUID low = new UUID(0, 1);
    UUID high = new UUID(0, 9);

    List<UUID> order =
        Posting.lockOrder(
            List.of(
                new EntryDraft(high, Direction.DEBIT, 1),
                new EntryDraft(low, Direction.CREDIT, 1),
                new EntryDraft(high, Direction.CREDIT, 1)));

    assertThat(order).containsExactly(low, high);
  }
}
