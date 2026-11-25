package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.adapter.in.web.Api.AccountResponse;
import io.github.francisworld.ledger.adapter.in.web.Api.OpenAccountRequest;
import io.github.francisworld.ledger.adapter.in.web.Api.StatementLineResponse;
import io.github.francisworld.ledger.adapter.in.web.Api.StatementResponse;
import io.github.francisworld.ledger.application.port.in.AccountUseCase;
import io.github.francisworld.ledger.application.port.in.AccountUseCase.OpenAccount;
import io.github.francisworld.ledger.application.port.in.AccountUseCase.Statement;
import io.github.francisworld.ledger.application.port.in.AccountUseCase.StatementQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts")
class AccountController {

  private final AccountUseCase accounts;
  private final IdempotentHandler idempotent;

  AccountController(AccountUseCase accounts, IdempotentHandler idempotent) {
    this.accounts = accounts;
    this.idempotent = idempotent;
  }

  @PostMapping
  @Operation(summary = "Open an account")
  ResponseEntity<AccountResponse> open(
      @RequestHeader(IdempotentHandler.HEADER) String key,
      @Valid @RequestBody OpenAccountRequest body) {
    return idempotent.handle(
        key,
        "POST /v1/accounts",
        body,
        HttpStatus.CREATED,
        AccountResponse.class,
        () ->
            AccountResponse.from(
                accounts.open(
                    new OpenAccount(
                        body.code(),
                        body.name(),
                        body.type(),
                        body.currency(),
                        Boolean.TRUE.equals(body.allowNegative())))));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Read an account with its balance, held and available amounts")
  AccountResponse get(@PathVariable UUID id) {
    return AccountResponse.from(accounts.get(id));
  }

  @GetMapping("/{id}/statement")
  @Operation(
      summary = "Entries of an account in posting order, each with the running balance",
      description = "Keyset pagination: pass the previous page's next_cursor as `after`.")
  StatementResponse statement(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) Long after,
      @RequestParam(defaultValue = "50") int limit) {
    Statement statement = accounts.statement(id, new StatementQuery(from, to, after, limit));
    return new StatementResponse(
        AccountResponse.from(statement.account()),
        statement.lines().stream().map(StatementLineResponse::from).toList(),
        statement.nextCursor());
  }
}
