package io.github.francisworld.ledger.adapter.out.persistence;

import io.github.francisworld.ledger.application.port.out.ApiKeyRepository;
import io.github.francisworld.ledger.domain.Principal;
import io.github.francisworld.ledger.domain.Scope;
import java.sql.Array;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcApiKeyRepository implements ApiKeyRepository {

  private final JdbcClient jdbc;

  JdbcApiKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Principal> findActiveByHash(String hash) {
    return jdbc.sql(
            "select id, name, scopes from api_keys where key_hash = :hash and revoked_at is null")
        .param("hash", hash)
        .query(
            (rs, n) -> {
              Array scopes = rs.getArray("scopes");
              Set<Scope> parsed =
                  Arrays.stream((String[]) scopes.getArray())
                      .map(Scope::parse)
                      .flatMap(Optional::stream)
                      .collect(Collectors.toSet());
              return new Principal(rs.getObject("id", UUID.class), rs.getString("name"), parsed);
            })
        .optional();
  }

  @Override
  public void insert(Principal principal, String hash) {
    String[] scopes = principal.scopes().stream().map(Scope::value).sorted().toArray(String[]::new);
    jdbc.sql(
            "insert into api_keys (id, name, key_hash, scopes) values (:id, :name, :hash, :scopes)")
        .param("id", principal.keyId())
        .param("name", principal.name())
        .param("hash", hash)
        .param("scopes", scopes)
        .update();
  }
}
