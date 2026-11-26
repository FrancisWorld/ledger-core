package io.github.francisworld.ledger.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL per test JVM. By default a Testcontainers container is started; set {@code
 * LEDGER_TEST_DB_URL} (plus {@code LEDGER_TEST_DB_USER} / {@code LEDGER_TEST_DB_PASSWORD}) to run
 * against an already running database instead, e.g. the one from {@code docker-compose.yml} when
 * the Docker socket is not reachable from Testcontainers. That user must own the schema.
 *
 * <p>Mirrors production: Flyway runs as the owner, the application runs as the unprivileged {@code
 * ledger_app} role created by migration V6.
 */
public final class PostgresTestSupport {

  public static final String RUNTIME_USER = "ledger_app";
  public static final String RUNTIME_PASSWORD = "test-runtime-password";

  private static final String EXTERNAL_URL = System.getenv("LEDGER_TEST_DB_URL");
  private static PostgreSQLContainer container;

  private PostgresTestSupport() {}

  public static synchronized void register(DynamicPropertyRegistry registry) {
    start();
    registry.add("spring.datasource.url", PostgresTestSupport::url);
    registry.add("spring.datasource.username", () -> RUNTIME_USER);
    registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    registry.add("spring.flyway.url", PostgresTestSupport::url);
    registry.add("spring.flyway.user", PostgresTestSupport::ownerUser);
    registry.add("spring.flyway.password", PostgresTestSupport::ownerPassword);
    registry.add("spring.flyway.placeholders.runtime_password", () -> RUNTIME_PASSWORD);
  }

  private static void start() {
    if (usesExternal() || container != null) {
      return;
    }
    // Durability is irrelevant for throwaway test data; skipping fsync keeps the load tests fast.
    container =
        new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off");
    container.start();
  }

  private static boolean usesExternal() {
    return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
  }

  public static String url() {
    return usesExternal() ? EXTERNAL_URL : container.getJdbcUrl();
  }

  public static String ownerUser() {
    return usesExternal() ? System.getenv("LEDGER_TEST_DB_USER") : container.getUsername();
  }

  public static String ownerPassword() {
    return usesExternal() ? System.getenv("LEDGER_TEST_DB_PASSWORD") : container.getPassword();
  }

  public static DriverManagerDataSource ownerDataSource() {
    return new DriverManagerDataSource(url(), ownerUser(), ownerPassword());
  }

  /** A client connected as the schema owner, for assertions the runtime role cannot make. */
  public static JdbcClient ownerJdbc() {
    return JdbcClient.create(ownerDataSource());
  }

  public static DriverManagerDataSource runtimeDataSource() {
    return new DriverManagerDataSource(url(), RUNTIME_USER, RUNTIME_PASSWORD);
  }
}
