package io.github.francisworld.ledger.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL per test JVM. By default a Testcontainers container is started; set {@code
 * LEDGER_TEST_DB_URL} (plus {@code LEDGER_TEST_DB_USER} / {@code LEDGER_TEST_DB_PASSWORD}) to run
 * against an already running database instead, e.g. the one from {@code docker-compose.yml} when
 * the Docker socket is not reachable from Testcontainers.
 */
public final class PostgresTestSupport {

  private static final String EXTERNAL_URL = System.getenv("LEDGER_TEST_DB_URL");
  private static PostgreSQLContainer container;

  private PostgresTestSupport() {}

  public static synchronized void register(DynamicPropertyRegistry registry) {
    if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
      registry.add("spring.datasource.url", () -> EXTERNAL_URL);
      registry.add("spring.datasource.username", () -> System.getenv("LEDGER_TEST_DB_USER"));
      registry.add("spring.datasource.password", () -> System.getenv("LEDGER_TEST_DB_PASSWORD"));
      return;
    }
    if (container == null) {
      // Durability is irrelevant for throwaway test data; skipping fsync keeps the load tests fast.
      container =
          new PostgreSQLContainer("postgres:16-alpine")
              .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off");
      container.start();
    }
    registry.add("spring.datasource.url", container::getJdbcUrl);
    registry.add("spring.datasource.username", container::getUsername);
    registry.add("spring.datasource.password", container::getPassword);
  }
}
