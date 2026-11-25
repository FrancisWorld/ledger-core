package io.github.francisworld.ledger.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Transactional outbox storage. */
public interface OutboxStore {

  /** An event produced by a business transaction. */
  record NewEvent(
      String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {}

  /** A stored event waiting for delivery. {@code payload} is serialized JSON. */
  record Message(
      long id,
      String aggregateType,
      String aggregateId,
      String eventType,
      String payload,
      Instant createdAt,
      int attempts) {}

  void append(NewEvent event);

  /** Locks up to {@code limit} deliverable messages, skipping rows another relay has locked. */
  List<Message> claim(Instant now, int limit);

  void markPublished(Collection<Long> ids, Instant now);

  /** Records a failed delivery; {@code nextAttemptAt == null} parks the message as dead. */
  void markFailed(long id, int attempts, Instant nextAttemptAt, String error);
}
