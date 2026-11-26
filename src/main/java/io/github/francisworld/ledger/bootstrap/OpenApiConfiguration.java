package io.github.francisworld.ledger.bootstrap;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  @Bean
  OpenAPI ledgerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("ledger-core")
                .version("0.1.0")
                .description(
                    """
                    Double-entry ledger. Amounts are integers in minor units (cents). Every POST \
                    requires an `Idempotency-Key` header: replaying a key with the same body returns \
                    the original response, with a different body it fails with 409.""")
                .license(new License().name("MIT")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("API key, sent as `Authorization: Bearer lck_...`")))
        .addSecurityItem(new SecurityRequirement().addList("apiKey"));
  }
}
