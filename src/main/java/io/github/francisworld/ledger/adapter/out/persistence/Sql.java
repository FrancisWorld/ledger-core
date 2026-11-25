package io.github.francisworld.ledger.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;

/** Small helpers shared by the JDBC adapters. */
final class Sql {

  private Sql() {}

  /** pgjdbc binds timestamptz via OffsetDateTime, not Instant. */
  static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  /** True when the unique violation was raised by the named constraint. */
  static boolean violatedConstraint(DuplicateKeyException e, String constraint) {
    return e.getCause() instanceof PSQLException pe
        && pe.getServerErrorMessage() != null
        && constraint.equals(pe.getServerErrorMessage().getConstraint());
  }
}
