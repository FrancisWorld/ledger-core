package io.github.francisworld.ledger.support;

import io.github.francisworld.ledger.domain.Scope;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for integration tests. Every subclass shares one Spring context and one database, with the
 * background relay and sweeper switched off so tests drive them explicitly. The journal is
 * append-only, so tests isolate themselves by creating fresh accounts rather than cleaning up.
 */
@SpringBootTest(
    classes = io.github.francisworld.ledger.bootstrap.LedgerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledger.outbox.relay-enabled=false",
      "ledger.holds.sweeper-enabled=false",
      "management.server.port=0",
      "spring.datasource.hikari.maximum-pool-size=16",
      "spring.datasource.hikari.connection-timeout=60000",
      "logging.level.io.github.francisworld.ledger.application.service.OutboxRelay=ERROR"
    })
public abstract class AbstractIT {

  @LocalServerPort protected int port;

  @LocalManagementPort protected int managementPort;

  @Autowired protected ObjectMapper json;

  /** Authenticated with a key holding every scope. */
  protected TestApi api;

  /** Management port: health and metrics, no credentials. */
  protected TestApi management;

  protected String fullAccessKey;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestSupport.register(registry);
  }

  @org.junit.jupiter.api.BeforeEach
  void connectClient() {
    fullAccessKey = TestKeys.issue("it-full", java.util.EnumSet.allOf(Scope.class));
    api = new TestApi("http://localhost:" + port, json, fullAccessKey);
    management = new TestApi("http://localhost:" + managementPort, json, null);
  }

  protected static String uniqueKey() {
    return "k-" + UUID.randomUUID();
  }

  protected static String uniqueCode(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
  }
}
