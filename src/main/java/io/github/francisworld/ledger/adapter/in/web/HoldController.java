package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.adapter.in.web.Api.CaptureRequest;
import io.github.francisworld.ledger.adapter.in.web.Api.CaptureResponse;
import io.github.francisworld.ledger.adapter.in.web.Api.HoldResponse;
import io.github.francisworld.ledger.adapter.in.web.Api.PlaceHoldRequest;
import io.github.francisworld.ledger.adapter.in.web.Api.TransactionResponse;
import io.github.francisworld.ledger.application.port.in.HoldUseCase;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.CaptureHold;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.Captured;
import io.github.francisworld.ledger.application.port.in.HoldUseCase.PlaceHold;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/holds")
@Tag(name = "Holds")
class HoldController {

  private final HoldUseCase holds;
  private final IdempotentHandler idempotent;

  HoldController(HoldUseCase holds, IdempotentHandler idempotent) {
    this.holds = holds;
    this.idempotent = idempotent;
  }

  @PostMapping
  @RequiresScope(Scope.HOLDS_WRITE)
  @Operation(
      summary = "Reserve funds on an account",
      description =
          "Reduces available (not posted) balance. Only credit-normal accounts can hold funds.")
  ResponseEntity<HoldResponse> place(
      @RequestHeader(IdempotentHandler.HEADER) String key,
      @Valid @RequestBody PlaceHoldRequest body) {
    return idempotent.handle(
        key,
        "POST /v1/holds",
        body,
        HttpStatus.CREATED,
        HoldResponse.class,
        () ->
            HoldResponse.from(
                holds.place(
                    new PlaceHold(
                        body.accountId(),
                        body.amount(),
                        body.currency(),
                        body.reference(),
                        body.expiresAt()))));
  }

  @GetMapping("/{id}")
  @RequiresScope(Scope.HOLDS_READ)
  @Operation(summary = "Read a hold")
  HoldResponse get(@PathVariable UUID id) {
    return HoldResponse.from(holds.get(id));
  }

  @PostMapping("/{id}/capture")
  @RequiresScope(Scope.HOLDS_WRITE)
  @Operation(
      summary = "Capture a hold into a transfer",
      description =
          "Debits the held account and credits the destination. Whatever part of the hold is not captured is released.")
  ResponseEntity<CaptureResponse> capture(
      @RequestHeader(IdempotentHandler.HEADER) String key,
      @PathVariable UUID id,
      @Valid @RequestBody CaptureRequest body) {
    return idempotent.handle(
        key,
        "POST /v1/holds/" + id + "/capture",
        body,
        HttpStatus.OK,
        CaptureResponse.class,
        () -> {
          Captured captured =
              holds.capture(
                  new CaptureHold(
                      id, body.destinationAccountId(), body.amount(), body.reference()));
          return new CaptureResponse(
              HoldResponse.from(captured.hold()), TransactionResponse.from(captured.transaction()));
        });
  }

  @PostMapping("/{id}/release")
  @RequiresScope(Scope.HOLDS_WRITE)
  @Operation(summary = "Release a hold, freeing the reserved funds")
  ResponseEntity<HoldResponse> release(
      @RequestHeader(IdempotentHandler.HEADER) String key, @PathVariable UUID id) {
    return idempotent.handle(
        key,
        "POST /v1/holds/" + id + "/release",
        id,
        HttpStatus.OK,
        HoldResponse.class,
        () -> HoldResponse.from(holds.release(id)));
  }
}
