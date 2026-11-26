package io.github.francisworld.ledger.application.service;

import io.github.francisworld.ledger.application.port.in.ApiKeyUseCase;
import io.github.francisworld.ledger.application.port.out.ApiKeyRepository;
import io.github.francisworld.ledger.application.port.out.IdGenerator;
import io.github.francisworld.ledger.domain.LedgerException;
import io.github.francisworld.ledger.domain.Principal;
import io.github.francisworld.ledger.domain.Scope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Keys are 256 random bits, so a plain SHA-256 digest is enough to store them safely (there is
 * nothing to brute-force, unlike passwords); the plaintext is shown once and never persisted.
 */
public final class ApiKeyService implements ApiKeyUseCase {

  static final String PREFIX = "lck_";

  private final ApiKeyRepository repository;
  private final IdGenerator ids;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyService(ApiKeyRepository repository, IdGenerator ids) {
    this.repository = repository;
    this.ids = ids;
  }

  /** Digest under which a key is stored and looked up. */
  public static String hash(String plaintext) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
    }
  }

  @Override
  public Optional<Principal> authenticate(String presentedKey) {
    if (presentedKey == null || !presentedKey.startsWith(PREFIX) || presentedKey.length() > 128) {
      return Optional.empty();
    }
    return repository.findActiveByHash(hash(presentedKey));
  }

  @Override
  public IssuedKey issue(String name, Set<Scope> scopes) {
    if (name == null || name.isBlank() || scopes.isEmpty()) {
      throw LedgerException.invalid("invalid-api-key", "A key needs a name and at least one scope");
    }
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    Principal principal = new Principal(ids.next(), name, scopes);
    repository.insert(principal, hash(plaintext));
    return new IssuedKey(principal, plaintext);
  }
}
