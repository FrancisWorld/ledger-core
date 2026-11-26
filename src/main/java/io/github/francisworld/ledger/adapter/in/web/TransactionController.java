package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.adapter.in.web.Api.JournalRequest;
import io.github.francisworld.ledger.adapter.in.web.Api.ReversalRequest;
import io.github.francisworld.ledger.adapter.in.web.Api.TransactionResponse;
import io.github.francisworld.ledger.adapter.in.web.Api.TransferRequest;
import io.github.francisworld.ledger.application.port.in.PostingUseCase;
import io.github.francisworld.ledger.application.port.in.PostingUseCase.Journal;
import io.github.francisworld.ledger.application.port.in.PostingUseCase.JournalLine;
import io.github.francisworld.ledger.application.port.in.PostingUseCase.Transfer;
import io.github.francisworld.ledger.domain.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Transactions")
class TransactionController {

  private final PostingUseCase posting;
  private final IdempotentHandler idempotent;

  TransactionController(PostingUseCase posting, IdempotentHandler idempotent) {
    this.posting = posting;
    this.idempotent = idempotent;
  }

  @PostMapping("/v1/transfers")
  @RequiresScope(Scope.TRANSACTIONS_WRITE)
  @Operation(
      summary = "Move money between two accounts",
      description =
          "Debits the source and credits the destination. Needs spendable funds on the source unless it allows negative balances.")
  ResponseEntity<TransactionResponse> transfer(
      @RequestHeader(IdempotentHandler.HEADER) String key,
      @Valid @RequestBody TransferRequest body) {
    return idempotent.handle(
        key,
        "POST /v1/transfers",
        body,
        HttpStatus.CREATED,
        TransactionResponse.class,
        () ->
            TransactionResponse.from(
                posting.transfer(
                    new Transfer(
                        body.sourceAccountId(),
                        body.destinationAccountId(),
                        body.amount(),
                        body.currency(),
                        body.reference(),
                        body.description()))));
  }

  @PostMapping("/v1/transactions")
  @RequiresScope(Scope.TRANSACTIONS_WRITE)
  @Operation(
      summary = "Post a free-form journal entry",
      description =
          "Any number of debit and credit lines; debits must equal credits in every currency.")
  ResponseEntity<TransactionResponse> post(
      @RequestHeader(IdempotentHandler.HEADER) String key,
      @Valid @RequestBody JournalRequest body) {
    return idempotent.handle(
        key,
        "POST /v1/transactions",
        body,
        HttpStatus.CREATED,
        TransactionResponse.class,
        () ->
            TransactionResponse.from(
                posting.post(
                    new Journal(
                        body.lines().stream()
                            .map(l -> new JournalLine(l.accountId(), l.direction(), l.amount()))
                            .toList(),
                        body.reference(),
                        body.description()))));
  }

  @GetMapping("/v1/transactions/{id}")
  @RequiresScope(Scope.TRANSACTIONS_READ)
  @Operation(summary = "Read a transaction with its entries")
  TransactionResponse get(@PathVariable UUID id) {
    return TransactionResponse.from(posting.get(id));
  }

  @PostMapping("/v1/transactions/{id}/reversal")
  @RequiresScope(Scope.TRANSACTIONS_WRITE)
  @Operation(
      summary = "Reverse a transaction",
      description =
          "Posts a new transaction with every entry flipped. A transaction can be reversed once, and reversals cannot be reversed.")
  ResponseEntity<TransactionResponse> reverse(
      @RequestHeader(IdempotentHandler.HEADER) String key,
      @PathVariable UUID id,
      @Valid @RequestBody ReversalRequest body) {
    return idempotent.handle(
        key,
        "POST /v1/transactions/" + id + "/reversal",
        body,
        HttpStatus.CREATED,
        TransactionResponse.class,
        () -> TransactionResponse.from(posting.reverse(id, body.reason())));
  }
}
