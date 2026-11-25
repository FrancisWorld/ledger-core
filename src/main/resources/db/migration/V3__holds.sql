create table holds (
  id                     uuid primary key,
  account_id             uuid        not null,
  amount                 bigint      not null,
  currency               text        not null,
  status                 text        not null,
  reference              text,
  expires_at             timestamptz,
  captured_amount        bigint,
  capture_transaction_id uuid references transactions (id),
  created_at             timestamptz not null,
  updated_at             timestamptz not null,
  constraint holds_amount_positive check (amount > 0),
  constraint holds_status_valid check (status in ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
  constraint holds_capture_bounds check (captured_amount is null or captured_amount between 1 and amount),
  constraint holds_capture_consistent check ((status = 'CAPTURED') = (captured_amount is not null)),
  constraint holds_account_currency_fk foreign key (account_id, currency)
    references accounts (id, currency)
);

-- the expiry sweeper only ever looks at active holds with a deadline
create index holds_expiry_idx on holds (expires_at) where status = 'ACTIVE' and expires_at is not null;
create index holds_account_idx on holds (account_id);
