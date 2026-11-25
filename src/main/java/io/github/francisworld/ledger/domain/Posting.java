package io.github.francisworld.ledger.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure double-entry posting engine. Given the current state of the touched accounts and a list of
 * drafts it validates the journal, applies every line and returns the new account states plus the
 * entries with their running balances. Nothing here performs I/O, which is what makes the rules
 * property-testable.
 */
public final class Posting {

  /** Upper bound on lines per transaction; keeps lock sets and deferred DB checks bounded. */
  public static final int MAX_ENTRIES = 100;

  /** Maximum single-entry amount in minor units (10^15), far below {@code Long.MAX_VALUE}. */
  public static final long MAX_AMOUNT = 1_000_000_000_000_000L;

  public record Result(Map<UUID, Account> accounts, List<Entry> entries) {}

  private Posting() {}

  /** Ids of every account a journal touches, sorted: the canonical lock-acquisition order. */
  public static List<UUID> lockOrder(List<EntryDraft> drafts) {
    return drafts.stream()
        .map(EntryDraft::accountId)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  public static Result apply(Map<UUID, Account> accounts, List<EntryDraft> drafts) {
    validateShape(drafts);
    Map<UUID, Account> current = new HashMap<>(accounts);
    Map<String, Long> debitsMinusCredits = new HashMap<>();
    List<Entry> posted = new ArrayList<>(drafts.size());
    for (EntryDraft draft : drafts) {
      Account account = current.get(draft.accountId());
      if (account == null) {
        throw LedgerException.notFound(
            "account-not-found", "Account " + draft.accountId() + " does not exist");
      }
      Account next = account.apply(draft.direction(), draft.amount());
      current.put(account.id(), next);
      long signed = draft.direction() == Direction.DEBIT ? draft.amount() : -draft.amount();
      debitsMinusCredits.merge(account.currency(), signed, Long::sum);
      posted.add(
          new Entry(
              account.id(), draft.direction(), draft.amount(), account.currency(), next.balance()));
    }
    current.values().forEach(Account::requireSolvent);
    debitsMinusCredits.forEach(
        (currency, delta) -> {
          if (delta != 0) {
            throw LedgerException.unprocessable(
                "unbalanced-transaction",
                "Debits and credits differ by %d in %s".formatted(delta, currency));
          }
        });
    return new Result(current, List.copyOf(posted));
  }

  /** The drafts that exactly undo the given entries. */
  public static List<EntryDraft> inverse(List<Entry> entries) {
    return entries.stream()
        .map(e -> new EntryDraft(e.accountId(), e.direction().opposite(), e.amount()))
        .toList();
  }

  private static void validateShape(List<EntryDraft> drafts) {
    if (drafts.size() < 2) {
      throw LedgerException.invalid("too-few-entries", "A transaction needs at least two entries");
    }
    if (drafts.size() > MAX_ENTRIES) {
      throw LedgerException.invalid(
          "too-many-entries", "A transaction may have at most " + MAX_ENTRIES + " entries");
    }
    for (EntryDraft draft : drafts) {
      if (draft.amount() > MAX_AMOUNT) {
        throw LedgerException.invalid(
            "invalid-amount", "Entry amount exceeds the maximum of " + MAX_AMOUNT);
      }
    }
  }
}
