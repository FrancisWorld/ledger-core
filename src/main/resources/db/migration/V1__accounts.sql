-- Accounts hold the running balances. The overdraft rule lives here as well as in the domain model,
-- so no code path (including manual SQL) can leave a non-overdraftable account short of funds.
create table accounts (
  id             uuid primary key,
  code           text        not null,
  name           text        not null,
  type           text        not null,
  currency       text        not null,
  allow_negative boolean     not null default false,
  balance        bigint      not null default 0,
  held           bigint      not null default 0,
  created_at     timestamptz not null,
  constraint accounts_code_key unique (code),
  constraint accounts_id_currency_key unique (id, currency),
  constraint accounts_code_format check (code ~ '^[A-Za-z0-9._:-]{1,64}$'),
  constraint accounts_type_valid check (type in ('ASSET', 'EXPENSE', 'LIABILITY', 'EQUITY', 'REVENUE')),
  constraint accounts_currency_format check (currency ~ '^[A-Z]{3}$'),
  constraint accounts_held_non_negative check (held >= 0),
  constraint accounts_no_overdraft check (allow_negative or balance >= held)
);
