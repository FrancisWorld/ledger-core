-- Transactional outbox: rows are written in the same transaction as the business change and later
-- drained by the relay with FOR UPDATE SKIP LOCKED.
create table outbox (
  id              bigint generated always as identity primary key,
  aggregate_type  text        not null,
  aggregate_id    text        not null,
  event_type      text        not null,
  payload         jsonb       not null,
  status          text        not null default 'PENDING',
  attempts        int         not null default 0,
  next_attempt_at timestamptz not null,
  last_error      text,
  created_at      timestamptz not null,
  published_at    timestamptz,
  constraint outbox_status_valid check (status in ('PENDING', 'PUBLISHED', 'DEAD'))
);

create index outbox_pending_idx on outbox (id) where status = 'PENDING';

-- Idempotency-Key bookkeeping. The row is written in the same transaction as the request's effects.
create table idempotency_keys (
  key             text primary key,
  fingerprint     text        not null,
  response_status int,
  response_body   text,
  created_at      timestamptz not null default now(),
  constraint idempotency_key_length check (length(key) between 1 and 255)
);
