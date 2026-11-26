-- API clients. Only a SHA-256 digest of each key is stored; scopes limit what a key may call.
create table api_keys (
  id         uuid primary key,
  name       text        not null,
  key_hash   text        not null,
  scopes     text[]      not null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  constraint api_keys_hash_key unique (key_hash),
  constraint api_keys_scopes_not_empty check (cardinality(scopes) > 0)
);
