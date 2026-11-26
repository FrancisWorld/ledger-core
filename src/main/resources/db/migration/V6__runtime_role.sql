-- Least-privilege runtime role. The application connects as ledger_app; schema changes, trigger
-- management and key issuing stay with the migration owner. ledger_app can read and write the
-- operational tables, may only append to the journal, and cannot TRUNCATE, run DDL, drop or
-- disable the append-only triggers (that needs table ownership).
--
-- The login password arrives as a Flyway placeholder so it never lives in this file.
do $$
begin
  if not exists (select from pg_roles where rolname = 'ledger_app') then
    create role ledger_app login password '${runtime_password}'
      nosuperuser nocreatedb nocreaterole noinherit;
  end if;
end
$$;

revoke all on all tables in schema public from ledger_app;
revoke create on schema public from public;
grant usage on schema public to ledger_app;

grant select, insert, update on accounts, holds, outbox, idempotency_keys to ledger_app;
-- the journal is append-only: no UPDATE, DELETE or TRUNCATE privilege at all
grant select, insert on transactions, entries to ledger_app;
-- keys are read by the service and issued only by the owner
grant select on api_keys to ledger_app;
