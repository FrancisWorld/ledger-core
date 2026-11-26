package io.github.francisworld.ledger.support;

import io.github.francisworld.ledger.application.service.ApiKeyService;
import io.github.francisworld.ledger.domain.Scope;
import java.util.Set;
import java.util.UUID;

/** Issues API keys through the owner connection; the runtime role may only read them. */
public final class TestKeys {

  private TestKeys() {}

  public static String issue(String name, Set<Scope> scopes) {
    String plaintext = "lck_" + UUID.randomUUID().toString().replace("-", "");
    PostgresTestSupport.ownerJdbc()
        .sql(
            "insert into api_keys (id, name, key_hash, scopes) values (:id, :name, :hash, :scopes)")
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("hash", ApiKeyService.hash(plaintext))
        .param("scopes", scopes.stream().map(Scope::value).toArray(String[]::new))
        .update();
    return plaintext;
  }

  public static void revoke(String plaintext) {
    PostgresTestSupport.ownerJdbc()
        .sql("update api_keys set revoked_at = now() where key_hash = :hash")
        .param("hash", ApiKeyService.hash(plaintext))
        .update();
  }
}
