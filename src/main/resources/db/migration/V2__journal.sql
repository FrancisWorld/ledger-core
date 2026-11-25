-- The journal: transactions and their entries. Both tables are append-only and every transaction
-- must be balanced per currency, enforced by triggers so it holds for any writer.
create table transactions (
  id          uuid primary key,
  kind        text        not null,
  reference   text,
  description text,
  reverses_id uuid,
  hold_id     uuid,
  entry_count int         not null,
  created_at  timestamptz not null,
  constraint transactions_kind_valid check (kind in ('TRANSFER', 'JOURNAL', 'CAPTURE', 'REVERSAL')),
  constraint transactions_entry_count_min check (entry_count >= 2),
  constraint transactions_reversal_link check ((kind = 'REVERSAL') = (reverses_id is not null)),
  constraint transactions_reverses_id_key unique (reverses_id),
  constraint transactions_reverses_fk foreign key (reverses_id) references transactions (id)
);

create table entries (
  seq            bigint generated always as identity primary key,
  transaction_id uuid        not null references transactions (id),
  account_id     uuid        not null,
  direction      text        not null,
  amount         bigint      not null,
  currency       text        not null,
  balance_after  bigint      not null,
  created_at     timestamptz not null,
  constraint entries_direction_valid check (direction in ('DEBIT', 'CREDIT')),
  constraint entries_amount_positive check (amount > 0),
  -- an entry can only be booked in the currency of its account
  constraint entries_account_currency_fk foreign key (account_id, currency)
    references accounts (id, currency)
);

create index entries_account_seq_idx on entries (account_id, seq);
create index entries_transaction_idx on entries (transaction_id);

create function ledger_reject_mutation() returns trigger
language plpgsql as $$
begin
  raise exception '% on % rejected: the journal is append-only', tg_op, tg_table_name
    using errcode = 'restrict_violation';
end
$$;

create trigger transactions_append_only
  before update or delete on transactions
  for each row execute function ledger_reject_mutation();
create trigger transactions_no_truncate
  before truncate on transactions
  for each statement execute function ledger_reject_mutation();
create trigger entries_append_only
  before update or delete on entries
  for each row execute function ledger_reject_mutation();
create trigger entries_no_truncate
  before truncate on entries
  for each statement execute function ledger_reject_mutation();

-- Checked at COMMIT: the entries of a transaction must match the declared count (so nothing can be
-- appended to an old transaction later) and debits must equal credits in every currency.
create function ledger_assert_transaction_consistent() returns trigger
language plpgsql as $$
declare
  tx_id    uuid;
  expected int;
  actual   int;
  skew     record;
begin
  if tg_table_name = 'transactions' then
    tx_id := new.id;
  else
    tx_id := new.transaction_id;
  end if;

  select entry_count into expected from transactions where id = tx_id;
  select count(*) into actual from entries where transaction_id = tx_id;
  if actual <> expected then
    raise exception 'transaction % declares % entries but has %', tx_id, expected, actual
      using errcode = 'check_violation';
  end if;

  select currency,
         sum(case direction when 'DEBIT' then amount else -amount end) as net
    into skew
    from entries
   where transaction_id = tx_id
   group by currency
  having sum(case direction when 'DEBIT' then amount else -amount end) <> 0
   limit 1;
  if found then
    raise exception 'transaction % is unbalanced by % in %', tx_id, skew.net, skew.currency
      using errcode = 'check_violation';
  end if;
  return null;
end
$$;

create constraint trigger transactions_consistent
  after insert on transactions
  deferrable initially deferred
  for each row execute function ledger_assert_transaction_consistent();
create constraint trigger entries_consistent
  after insert on entries
  deferrable initially deferred
  for each row execute function ledger_assert_transaction_consistent();
