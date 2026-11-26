package io.github.francisworld.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.francisworld.ledger.application.port.in.ApiKeyUseCase.IssuedKey;
import io.github.francisworld.ledger.application.port.out.ApiKeyRepository;
import io.github.francisworld.ledger.application.service.ApiKeyService;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Principal;
import io.github.francisworld.ledger.domain.Scope;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

  private final Map<String, Principal> stored = new HashMap<>();
  private final ApiKeyRepository repository =
      new ApiKeyRepository() {
        @Override
        public Optional<Principal> findActiveByHash(String hash) {
          return Optional.ofNullable(stored.get(hash));
        }

        @Override
        public void insert(Principal principal, String hash) {
          stored.put(hash, principal);
        }
      };
  private final ApiKeyService service = new ApiKeyService(repository, UUID::randomUUID);

  @Test
  void anIssuedKeyAuthenticatesAndOnlyItsHashIsStored() {
    IssuedKey issued = service.issue("billing", EnumSet.of(Scope.TRANSACTIONS_WRITE));

    assertThat(issued.plaintext()).startsWith("lck_").hasSizeGreaterThan(40);
    assertThat(stored).doesNotContainKey(issued.plaintext());
    assertThat(stored).containsKey(ApiKeyService.hash(issued.plaintext()));
    assertThat(service.authenticate(issued.plaintext()))
        .hasValueSatisfying(p -> assertThat(p.has(Scope.TRANSACTIONS_WRITE)).isTrue());
  }

  @Test
  void keysAreRandomAndUnknownOnesAreRejected() {
    IssuedKey a = service.issue("a", EnumSet.of(Scope.REPORTS_READ));
    IssuedKey b = service.issue("b", EnumSet.of(Scope.REPORTS_READ));

    assertThat(a.plaintext()).isNotEqualTo(b.plaintext());
    assertThat(service.authenticate(a.plaintext() + "x")).isEmpty();
    assertThat(service.authenticate("lck_" + "x".repeat(200))).isEmpty();
    assertThat(service.authenticate(null)).isEmpty();
    assertThat(service.authenticate("")).isEmpty();
  }

  @Test
  void aKeyNeedsANameAndAtLeastOneScope() {
    assertThatThrownBy(() -> service.issue("", EnumSet.of(Scope.REPORTS_READ)))
        .isInstanceOf(LedgerException.class);
    assertThatThrownBy(() -> service.issue("x", EnumSet.noneOf(Scope.class)))
        .isInstanceOf(LedgerException.class);
  }
}
