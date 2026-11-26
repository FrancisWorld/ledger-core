package io.github.francisworld.ledger.bootstrap;

import io.github.francisworld.ledger.application.port.in.ApiKeyUseCase;
import io.github.francisworld.ledger.domain.Scope;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One-shot operating modes that run with the same image as the service and then exit:
 *
 * <ul>
 *   <li>{@code ledger.migrate-only=true}: Flyway has already run during startup (with the migration
 *       credentials), so just exit. Used as a deploy step, so the long-running service can connect
 *       as the unprivileged runtime role with Flyway disabled.
 *   <li>{@code ledger.admin.issue-key.name}: create an API key, print it once and exit. Needs a
 *       database role that may insert into {@code api_keys}, i.e. the owner, not the runtime role.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class MaintenanceRunners {

  @Bean
  @ConditionalOnProperty(name = "ledger.migrate-only", havingValue = "true")
  ApplicationRunner migrateOnly(ApplicationContext context) {
    return args -> exit(context);
  }

  @Bean
  @ConditionalOnProperty(name = "ledger.admin.issue-key.name")
  ApplicationRunner issueKey(
      ApplicationContext context,
      ApiKeyUseCase apiKeys,
      @Value("${ledger.admin.issue-key.name}") String name,
      @Value("${ledger.admin.issue-key.scopes:all}") String scopes) {
    return args -> {
      Set<Scope> parsed =
          scopes.equals("all")
              ? EnumSet.allOf(Scope.class)
              : Arrays.stream(scopes.split(","))
                  .map(String::trim)
                  .map(
                      s ->
                          Scope.parse(s)
                              .orElseThrow(
                                  () -> new IllegalArgumentException("unknown scope " + s)))
                  .collect(Collectors.toSet());
      ApiKeyUseCase.IssuedKey issued = apiKeys.issue(name, parsed);
      System.out.println("API_KEY=" + issued.plaintext());
      exit(context);
    };
  }

  private static void exit(ApplicationContext context) {
    // exit from a separate thread so the runner returns and the context closes cleanly first
    Thread.ofPlatform().start(() -> System.exit(SpringApplication.exit(context)));
  }
}
