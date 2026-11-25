package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.application.port.in.AccountUseCase;
import io.github.francisworld.ledger.application.port.in.ReportUseCase;
import io.github.francisworld.ledger.domain.Account;
import io.github.francisworld.ledger.domain.AccountType;
import io.github.francisworld.ledger.domain.Direction;
import io.github.francisworld.ledger.domain.Hold;
import io.github.francisworld.ledger.domain.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire representations. JSON is snake_case and every amount is an integer in minor units. */
final class Api {

  private static final long MAX_AMOUNT = 1_000_000_000_000_000L;
  private static final String CURRENCY = "[A-Z]{3}";

  private Api() {}

  record OpenAccountRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String code,
      @NotBlank @Size(max = 200) String name,
      @NotNull AccountType type,
      @NotBlank @Pattern(regexp = CURRENCY) String currency,
      @Schema(description = "Allow the balance to go below zero (e.g. treasury or cash accounts)")
          Boolean allowNegative) {}

  record AccountResponse(
      UUID id,
      String code,
      String name,
      AccountType type,
      String currency,
      boolean allowNegative,
      @Schema(description = "Posted balance in minor units, on the account's normal side")
          long balance,
      @Schema(description = "Part of the balance reserved by active holds") long held,
      @Schema(description = "balance - held") long available,
      Instant createdAt) {

    static AccountResponse from(Account a) {
      return new AccountResponse(
          a.id(),
          a.code(),
          a.name(),
          a.type(),
          a.currency(),
          a.allowNegative(),
          a.balance(),
          a.held(),
          a.available(),
          a.createdAt());
    }
  }

  record TransferRequest(
      @NotNull UUID sourceAccountId,
      @NotNull UUID destinationAccountId,
      @Positive @Max(MAX_AMOUNT) long amount,
      @NotBlank @Pattern(regexp = CURRENCY) String currency,
      @Size(max = 200) String reference,
      @Size(max = 500) String description) {}

  record JournalLineRequest(
      @NotNull UUID accountId,
      @NotNull Direction direction,
      @Positive @Max(MAX_AMOUNT) long amount) {}

  record JournalRequest(
      @NotEmpty @Size(max = 100) List<@Valid @NotNull JournalLineRequest> lines,
      @Size(max = 200) String reference,
      @Size(max = 500) String description) {}

  record ReversalRequest(@NotBlank @Size(max = 500) String reason) {}

  record EntryResponse(
      UUID accountId, Direction direction, long amount, String currency, long balanceAfter) {}

  record TransactionResponse(
      UUID id,
      String kind,
      String reference,
      String description,
      UUID reversesId,
      UUID holdId,
      Instant createdAt,
      List<EntryResponse> entries) {

    static TransactionResponse from(Transaction t) {
      return new TransactionResponse(
          t.id(),
          t.kind().name(),
          t.reference(),
          t.description(),
          t.reversesId(),
          t.holdId(),
          t.createdAt(),
          t.entries().stream()
              .map(
                  e ->
                      new EntryResponse(
                          e.accountId(), e.direction(), e.amount(), e.currency(), e.balanceAfter()))
              .toList());
    }
  }

  record PlaceHoldRequest(
      @NotNull UUID accountId,
      @Positive @Max(MAX_AMOUNT) long amount,
      @NotBlank @Pattern(regexp = CURRENCY) String currency,
      @Size(max = 200) String reference,
      @Schema(description = "Optional deadline after which the hold is released automatically")
          Instant expiresAt) {}

  record CaptureRequest(
      @NotNull UUID destinationAccountId,
      @Schema(description = "Amount to capture; defaults to the full hold")
          @Positive
          @Max(MAX_AMOUNT)
          Long amount,
      @Size(max = 200) String reference) {}

  record HoldResponse(
      UUID id,
      UUID accountId,
      long amount,
      String currency,
      String status,
      String reference,
      Instant expiresAt,
      Long capturedAmount,
      UUID captureTransactionId,
      Instant createdAt,
      Instant updatedAt) {

    static HoldResponse from(Hold h) {
      return new HoldResponse(
          h.id(),
          h.accountId(),
          h.amount(),
          h.currency(),
          h.status().name(),
          h.reference(),
          h.expiresAt(),
          h.capturedAmount(),
          h.captureTransactionId(),
          h.createdAt(),
          h.updatedAt());
    }
  }

  record CaptureResponse(HoldResponse hold, TransactionResponse transaction) {}

  record StatementLineResponse(
      long seq,
      UUID transactionId,
      String kind,
      String reference,
      String direction,
      long amount,
      String currency,
      long balanceAfter,
      Instant createdAt) {

    static StatementLineResponse from(AccountUseCase.StatementLine l) {
      return new StatementLineResponse(
          l.seq(),
          l.transactionId(),
          l.kind(),
          l.reference(),
          l.direction(),
          l.amount(),
          l.currency(),
          l.balanceAfter(),
          l.createdAt());
    }
  }

  record StatementResponse(
      AccountResponse account,
      List<StatementLineResponse> lines,
      @Schema(description = "Pass as `after` to fetch the next page; null on the last page")
          Long nextCursor) {}

  record CurrencyTotalsResponse(
      String currency,
      long debitNormalTotal,
      long creditNormalTotal,
      long driftedAccounts,
      boolean balanced) {

    static CurrencyTotalsResponse from(ReportUseCase.CurrencyTotals t) {
      return new CurrencyTotalsResponse(
          t.currency(),
          t.debitNormalTotal(),
          t.creditNormalTotal(),
          t.driftedAccounts(),
          t.balanced());
    }
  }

  record TrialBalanceResponse(boolean balanced, List<CurrencyTotalsResponse> currencies) {}
}
