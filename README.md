# Task Scheduling Service

A distributed job scheduling service: jobs are submitted over a REST API,
persisted in PostgreSQL, and executed by a pool of concurrent workers that
claim work atomically, retry failures with exponential backoff, and recover
jobs orphaned by crashed workers.

The application is Java 21 on Spring Boot; the test suite is Kotlin.

## Layout

    src/main/java/com/scheduler/
      domain/      JPA entities: Job (state machine, @Version), AppUser
      repository/  Spring Data interfaces; the SKIP LOCKED claim query
      service/     transaction boundaries, retry/backoff policy
      controller/  REST endpoints, validation, error mapping
      security/    JWT issue/verify, auth filter, Spring Security chain
      worker/      scheduled poll loop, thread pool execution
      config/      thread pool definition
    src/test/kotlin/com/scheduler/
      JobQueueIntegrationTest.kt   concurrency, retry, reaper
      ApiIntegrationTest.kt        auth, validation, endpoint security

## Running

    docker compose up --build

Then:

    # register and capture a token
    TOKEN=$(curl -s -X POST localhost:8080/api/auth/register \
      -H 'Content-Type: application/json' \
      -d '{"username":"demo","password":"password123"}' | jq -r .token)

    # submit a job
    curl -X POST localhost:8080/api/jobs \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"name":"send-email","payload":"{}"}'

    # queue depth by status
    curl -s localhost:8080/api/jobs/stats -H "Authorization: Bearer $TOKEN"

Tests (requires a running Docker daemon for Testcontainers):

    ./gradlew test

Load test:

    python scripts/loadtest.py http://localhost:8080 5000

## API

    POST /api/auth/register    create a user, returns a JWT
    POST /api/auth/login       exchange credentials for a JWT
    POST /api/jobs             submit a job
    GET  /api/jobs/{id}        fetch one job
    GET  /api/jobs?status=     list jobs by status
    GET  /api/jobs/stats       queue depth by status

All /api/jobs routes require a Bearer token.

## Design

**Claiming work exactly once.** Workers do not read a job and then run it;
they atomically flip PENDING to RUNNING in a single UPDATE whose sub-select
takes row locks with `FOR UPDATE SKIP LOCKED`. Postgres serialises those
updates, so exactly one worker claims each row. SKIP LOCKED is what makes it
scale: without it, every worker contends for the same oldest rows, blocks,
and the queue degrades to single-worker throughput regardless of how many
are running. With it, each worker walks away with a disjoint batch.

Optimistic locking (`@Version`) guards the subsequent status writes:
Hibernate appends `AND version = ?` to each UPDATE, so a stale entity cannot
silently overwrite a concurrent modification.

The guarantee is exactly-once *claiming*. A worker that dies after
performing a side effect but before committing SUCCEEDED will have its job
reaped and retried, which is at-least-once in that window. End-to-end
exactly-once would require the side effect and the state change to share a
transaction, which is not possible for external calls; idempotent handlers
are the standard answer.

**Crash recovery.** A killed worker leaves rows stuck in RUNNING that
nothing would otherwise claim. A reaper returns rows whose claim is older
than a timeout to PENDING. The timeout is a real trade-off: too short and a
slow-but-alive worker has its job stolen; too long and recovery drags.

**Backoff.** Retries are delayed by `2^attempts * 2s`, capped at 10 minutes.
Constant-interval retries hammer a struggling dependency at a fixed rate;
doubling gives it room to recover. The cap bounds the worst case.

**Backpressure.** The executor queue is bounded and the rejection policy is
CallerRunsPolicy, so when the pool saturates the polling thread executes the
task itself and therefore stops claiming more work. Pressure propagates back
to the source instead of accumulating in memory. An unbounded queue would
grow until the process died.

**Stateless auth.** JWTs are signed, not encrypted: the claims are readable
by anyone holding the token, and the signature only proves they were not
altered. No server-side session means any instance can serve any request,
at the cost of not being able to revoke a token before expiry — hence short
lifetimes.

**Why Kotlin for tests.** Java for the application, Kotlin for tests. Both
compile to JVM bytecode so Kotlin tests call Java classes with no bridging.
Kotlin earns its place specifically in test code: backtick method names
read as sentences in reports, `apply` builds fixtures without boilerplate,
and string templates keep assertions legible.

**Why Testcontainers rather than H2.** The claim query depends on
PostgreSQL's `FOR UPDATE SKIP LOCKED` and on real row-level locking under
concurrent transactions. H2 does not faithfully emulate either, so a test
suite on H2 would pass while proving nothing about the database that
actually ships. Testcontainers boots real Postgres in Docker per run.

**Schema management.** `ddl-auto: update` lets Hibernate derive the schema
from the entities, which suits a project this size. Production would use
Flyway or Liquibase with reviewed, versioned migrations, since `update`
never drops or renames and cannot be rolled back.
