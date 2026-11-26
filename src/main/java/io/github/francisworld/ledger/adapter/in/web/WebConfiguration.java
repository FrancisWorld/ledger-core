package io.github.francisworld.ledger.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

  private final ApiKeyInterceptor apiKeys;

  WebConfiguration(ApiKeyInterceptor apiKeys) {
    this.apiKeys = apiKeys;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(apiKeys).addPathPatterns("/v1/**");
  }
}
