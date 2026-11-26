# ledger-core

**English** | [Português (Brasil)](README.pt-BR.md)

[![CI](https://github.com/FrancisWorld/ledger-core/actions/workflows/ci.yml/badge.svg)](https://github.com/FrancisWorld/ledger-core/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A double-entry accounting ledger API in Java 21 and Spring Boot. Money is moved only as balanced journal entries, and the rules that keep the books honest are enforced twice: in the domain model and again inside PostgreSQL.

## Why it exists, and what is hard about it

A ledger looks like "update two balances" until concurrency, retries and crashes show up. This project is a worked answer to the usual failure modes:

| Problem | How it is handled |
| --- | --- |
| Two requests spend the same money | Rows are locked with `SELECT ... FOR UPDATE`, always in ascending account-id order, so concurrent transfers serialize per account and cannot deadlock. A `CHECK` constraint backs the overdraft rule in the database. |
| Client retries a timed-out request | `Idempotency-Key` on every `POST`. The key row and the business writes commit in one transaction, so a retry replays the stored response and money moves once. Same key with a different body is `409`. |
| Someone edits history | `entries` and `transactions` are append-only (triggers reject `UPDATE`, `DELETE` and `TRUNCATE`), and a deferred constraint trigger refuses to commit a transaction whose debits and credits differ per currency. |
| Publishing an event and committing data can disagree | Transactional outbox: events are written in the same transaction as the change and relayed with `FOR UPDATE SKIP LOCKED`, at-least-once. |
| Card-style authorizations | Holds reserve funds (`available = balance - held`), can be captured (fully or partially) or released, and expire automatically. |
| Mistakes | Reversals post a new, mirrored transaction; a transaction can be reversed once and the database enforces that with a unique index. |

Amounts are integer minor units plus an ISO 4217 currency. There are no floats or decimals anywhere.

## Architecture

```mermaid
flowchart LR
  client([HTTP client]) --> web
  subgraph app[ledger-core]
    web[adapter.in.web<br/>controllers, problem+json,<br/>idempotency] --> usecases
    subgraph core[hexagon]
      usecases[application<br/>use cases + ports] --> domain[domain<br/>Account, Posting, Hold]
    end
    usecases --> persistence[adapter.out.persistence<br/>JDBC repositories]
    usecases --> messaging[adapter.out.messaging<br/>log / SQS sink]
    jobs[bootstrap<br/>outbox relay, hold sweeper] --> usecases
  end
  persistence --> pg[(PostgreSQL<br/>constraints + triggers)]
  messaging --> sqs[[SQS queue]]
  pg -. outbox rows .-> jobs
```

`domain` and `application` are plain Java with no framework imports. ArchUnit rules in `ArchitectureTest` fail the build if a dependency points the wrong way (for example the application importing Spring or JDBC, or the web layer touching SQL).

## Quickstart

Requirements: JDK 21 and Docker (for PostgreSQL).

```bash
cp .env.example .env                  # placeholder password, edit if you like
docker compose up --build -d          # PostgreSQL + the service on http://localhost:8080
```

Create three accounts (`cash` is an asset that may go negative, the wallets are liabilities), fund Alice, then transfer. This is real output from a local run (JAR against PostgreSQL 16); ids are UUIDv7:

```text
$ curl -si -X POST /v1/transfers -H "Idempotency-Key: demo-1" -d {alice -> bob, 2500 USD}
HTTP/1.1 201 
Content-Type: application/json
{
  "id": "01a21533-afeb-72f9-81aa-dfbbb0766754",
  "kind": "TRANSFER",
  "reference": null,
  "description": null,
  "reverses_id": null,
  "hold_id": null,
  "created_at": "2026-11-26T00:13:58.635886Z",
  "entries": [
    {
      "account_id": "01a21533-af6a-727f-817e-02fd718ffcc3",
      "direction": "DEBIT",
      "amount": 2500,
      "currency": "USD",
      "balance_after": 7500
    },
    {
      "account_id": "01a21533-af8d-74b6-80ad-a948e316a1c6",
      "direction": "CREDIT",
      "amount": 2500,
      "currency": "USD",
      "balance_after": 2500
    }
  ]
}

$ same request again: replayed, money moves once
HTTP/1.1 201 
Idempotent-Replayed: true

$ same key, different amount
{
    "detail": "Idempotency-Key was already used with a different request",
    "status": 409,
    "title": "idempotency key reuse",
    "type": "urn:ledger-core:problem:idempotency-key-reuse",
    "code": "idempotency-key-reuse"
}

$ overspend
{
    "detail": "Account alice would be left with -92499 available, which is not allowed",
    "status": 422,
    "title": "insufficient funds",
    "type": "urn:ledger-core:problem:insufficient-funds",
    "code": "insufficient-funds"
}

$ GET /v1/trial-balance
{
    "balanced": true,
    "currencies": [
        {
            "currency": "USD",
            "debit_normal_total": 10000,
            "credit_normal_total": 10000,
            "drifted_accounts": 0,
            "balanced": true
        }
    ]
}
```

Swagger UI is served at `/swagger-ui`, the OpenAPI document at `/v3/api-docs`, health at `/actuator/health`, Prometheus metrics at `/actuator/prometheus`.

To run from source against the compose database:

```bash
docker compose up -d postgres
LEDGER_DB_PASSWORD=replace-me ./mvnw spring-boot:run
```

## Design decisions and trade-offs

- **Pessimistic locking over optimistic retries.** Account rows are the contention point, and a ledger must never reject a valid transfer because someone else touched the account. Locks are taken in a total order (ascending id), which is what makes the design deadlock-free, and a transaction touches at most 100 accounts. The cost is that a very hot account serializes its writers; see Limitations.
- **Overdraft is judged on the final state of a journal**, not line by line, so the order of lines inside a transaction is irrelevant. Accounts opt out with `allow_negative` (cash, treasury, clearing accounts).
- **Account type decides the normal side.** Assets and expenses grow with debits, liabilities, equity and revenue grow with credits, so a customer wallet is a credit-normal liability and `balance` is always the natural, positive figure.
- **Every entry stores `balance_after`.** Statements need no window function, and the trial-balance endpoint can replay all entries and prove that each stored balance matches its history.
- **Idempotency lives in the same transaction as the work.** There is no "in progress" state to get stuck in after a crash. The cost: a request that fails (for example insufficient funds) leaves no key behind and is simply re-evaluated on retry. The fingerprint is a hash of the operation plus the canonical re-serialization of the parsed body, so whitespace and field order do not matter.
- **Outbox delivery is at-least-once.** A crash after the broker accepted a message but before the batch commits re-delivers it, and consumers de-duplicate on the `event_id` message attribute. Messages that keep failing back off exponentially and are finally parked as `DEAD` instead of blocking the queue.
- **Holds** are restricted to credit-normal accounts (wallets). A capture dissolves the whole reservation: the captured part becomes a debit and the rest is free again.
- **Plain JDBC (`JdbcClient`) instead of JPA.** The interesting behaviour is the SQL, so it is written and tested as SQL.
- **UUIDv7 identifiers** keep primary-key inserts roughly time-ordered.

## Testing strategy

`./mvnw verify` runs everything. Integration tests start PostgreSQL 16 with Testcontainers.

| Layer | What it proves |
| --- | --- |
| Domain unit tests | Normal sides, balance maths, overflow, hold state machine, journal validation. |
| jqwik properties | Over random worlds and journals: the accounting equation always holds, accepted journals never overdraw a wallet, line order never changes the outcome, a reversal restores every balance, running balances match final state. |
| `DatabaseInvariantsIT` | Raw SQL that bypasses the application: unbalanced entries fail at commit, history cannot be updated, deleted or truncated, accounts cannot overdraw, entries must match their account currency. |
| `LedgerApiIT` | End-to-end HTTP behaviour, `application/problem+json` shapes, statements and pagination, holds, reversals, OpenAPI and metrics. |
| `IdempotencyIT` | Replays, key reuse conflicts, canonical fingerprints, retry after failure, and 24 concurrent duplicates executing exactly once. |
| `ConcurrencyIT` | A mixed transfer / hold / capture / release / reversal workload from many threads, then an audit: money is conserved, nothing is overdrawn, held funds equal active holds, every account's running-balance chain is unbroken, the trial balance is balanced. Also opposing transfers (no deadlock), contended spending and holds (never overdrawn), racing reversals (exactly one wins). |
| `OutboxIT` | Events commit atomically with their change, four concurrent relays deliver each message once, a simulated crash after delivery re-delivers instead of losing, poison messages back off and are parked. |
| `ArchitectureTest` | The hexagonal dependency rules. |

A correctness workload, not a benchmark: `ConcurrencyIT` runs 24 threads x 120 mixed operations (transfers, holds, captures, releases, reversals) over 12 wallets; in my last run 2426 operations succeeded and 454 were legitimately rejected (insufficient funds, hold already settled, already reversed), taking about 5.4 s, after which every invariant audit passed. Measured on an Apple Silicon laptop (M1, 8 GB) with PostgreSQL running in a Colima VM with `fsync=off`, so the throughput says nothing about production capacity.

Run the integration tests without Testcontainers by pointing them at any PostgreSQL 16 (for example the compose one) with `LEDGER_TEST_DB_URL`, `LEDGER_TEST_DB_USER` and `LEDGER_TEST_DB_PASSWORD`. On Colima, Testcontainers needs `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

Code style is gated by Spotless (google-java-format) and Checkstyle, both bound to the `validate` phase; run `./mvnw spotless:apply` to format. The compiler runs with `-Xlint:all -Werror`.

## Infrastructure

`infra/` holds an OpenTofu configuration for AWS: VPC with public and private subnets, an Application Load Balancer, ECS Fargate service with autoscaling, RDS PostgreSQL (credentials generated and stored by RDS in Secrets Manager, never in state or config), an SQS queue with a dead-letter queue for the outbox relay, ECR, and least-privilege IAM (the execution role can pull this one image, write this log group and read this one secret; the task role can only `sqs:SendMessage` to the events queue).

It is validated, never applied:

```bash
cd infra && tofu fmt -check && tofu init -backend=false && tofu validate
```

## Limitations

- No authentication or authorization; it is meant to sit behind a gateway or service mesh that provides them.
- A single hot account serializes its writers (row lock per account). There is no sharding or batching of postings.
- Idempotency keys are stored forever; there is no TTL purge job.
- Only the final response of a successful request is cached; failed requests are re-executed.
- Outbox ordering is per insertion, not guaranteed across concurrent relays, and delivery is at-least-once.
- Single database, no partitioning of `entries`; the append-only journal will grow without bound.
- Cross-currency transfers are possible only as explicit multi-line journals through clearing accounts; there is no exchange-rate engine.
- Only the Dockerfile and the OpenTofu configuration were validated by building and `tofu validate`; the AWS stack has never been applied.

## License

[MIT](LICENSE)

---

Built by [Chico Oliveira](https://github.com/FrancisWorld)
