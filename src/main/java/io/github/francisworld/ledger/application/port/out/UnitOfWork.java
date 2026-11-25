package io.github.francisworld.ledger.application.port.out;

import java.util.function.Supplier;

/** Runs a block atomically. Nested calls join the surrounding unit of work. */
public interface UnitOfWork {

  <T> T execute(Supplier<T> work);

  default void run(Runnable work) {
    execute(
        () -> {
          work.run();
          return null;
        });
  }
}
