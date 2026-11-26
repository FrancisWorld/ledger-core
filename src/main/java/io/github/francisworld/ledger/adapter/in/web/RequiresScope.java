package io.github.francisworld.ledger.adapter.in.web;

import io.github.francisworld.ledger.domain.Scope;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The API-key scope a handler requires. A /v1 handler without it is rejected (fail closed). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface RequiresScope {

  Scope value();
}
