package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.domain.LedgerException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every failure as RFC 9457 {@code application/problem+json} with a stable {@code code}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final String TYPE_PREFIX = "urn:ledger-core:problem:";

  record FieldIssue(String field, String message) {}

  @ExceptionHandler(LedgerException.class)
  ResponseEntity<ProblemDetail> ledger(LedgerException e) {
    HttpStatus status =
        switch (e.kind()) {
          case INVALID -> HttpStatus.BAD_REQUEST;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
          case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    return problem(status, e.code(), e.getMessage());
  }

  @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
  ResponseEntity<ProblemDetail> transientFailure(Exception e) {
    LOG.warn("transient data access failure", e);
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "temporarily-unavailable",
            "The request could not be completed, retry with the same Idempotency-Key");
    return ResponseEntity.status(response.getStatusCode())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(response.getBody());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    LOG.error("unhandled exception", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected server error");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldIssue> issues =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new FieldIssue(f.getField(), String.valueOf(f.getDefaultMessage())))
            .toList();
    ProblemDetail detail =
        build(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed");
    detail.setProperty("errors", issues);
    return handleExceptionInternal(e, detail, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null
        && response.getBody() instanceof ProblemDetail detail
        && (detail.getProperties() == null || !detail.getProperties().containsKey("code"))) {
      detail.setProperty("code", "request-rejected");
    }
    return response;
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(build(status, code, message));
  }

  private static ProblemDetail build(HttpStatus status, String code, String message) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
    detail.setType(URI.create(TYPE_PREFIX + code));
    detail.setTitle(code.replace('-', ' '));
    detail.setProperty("code", code);
    return detail;
  }
}
