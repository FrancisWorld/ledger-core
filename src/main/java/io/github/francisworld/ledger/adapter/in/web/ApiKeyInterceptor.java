package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.application.port.in.ApiKeyUseCase;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates {@code Authorization: Bearer <api key>} and checks the handler's required scope.
 * Missing or unknown credentials are 401, a valid key without the scope is 403, and a handler that
 * forgot to declare a scope is also 403 so new endpoints are closed by default.
 */
@Component
class ApiKeyInterceptor implements HandlerInterceptor {

  static final String PRINCIPAL_ATTRIBUTE = ApiKeyInterceptor.class.getName() + ".principal";
  private static final String BEARER = "Bearer ";

  private final ApiKeyUseCase apiKeys;

  ApiKeyInterceptor(ApiKeyUseCase apiKeys) {
    this.apiKeys = apiKeys;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      throw unauthenticated(response, "Missing bearer API key");
    }
    Principal principal =
        apiKeys
            .authenticate(header.substring(BEARER.length()).trim())
            .orElseThrow(() -> unauthenticated(response, "Unknown or revoked API key"));
    RequiresScope required = method.getMethodAnnotation(RequiresScope.class);
    if (required == null || !principal.has(required.value())) {
      throw LedgerException.forbidden(
          required == null
              ? "Endpoint has no scope policy"
              : "API key lacks the scope " + required.value().value());
    }
    request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
    return true;
  }

  private static LedgerException unauthenticated(HttpServletResponse response, String message) {
    response.setHeader("WWW-Authenticate", "Bearer");
    return LedgerException.unauthenticated(message);
  }
}
