# database

The `database` module reads and writes PostgreSQL for every service. It holds the migrations in
`src/main/resources/db/migration` and the code for access, the season phase, online players, the
audit log, payments, update runs, inboxes and signals.

JDBI, HikariCP and the driver are `compileOnly`, so a consumer supplies its own runtime. The module
never migrates.

## Conventions

- `AccessDirectory` writes access. Plugins and the proxy only read it, through `AccessReader`.
- `SignalHub` is the only `LISTEN` connection of a process. It refreshes every `Channel` on connect,
  on each signal and once a minute. A `Doorbell` wakes the thread of a long task.
- An `Inbox` claims a request with `SKIP LOCKED` and announces each change on the table's channel.
  The answer is an `Outcome`: done, refused with a `Refusal`, or failed. A handler that throws fails
  its request, which is never retried.
- A pending request past its patience reads as expired. `scheduled_for` delays any request.
- `PREVIEW_MESSAGE` is the inbox kind every consumer of a text shares. It writes no journal line. A
  server refuses it with `NOT_HERE` when the player has left, and the bot with `NOT_DELIVERED`.
- A row stores a message as `MessageJson`, written by `DatabaseJson`, never as a sentence. The reader
  renders it.
- `Journal.write` is the only writer of `journal_entry`. A `JournalDetail` keeps its values in
  columns and renders its line when it is read.
- `:common`'s `Actor` says who asked. The row stores it as `actor_kind` and `actor_id`, never as a
  name.
- `Bookings` marks the payment as paid, appends the access through `Grants`, sets the donor flag,
  writes the journal line and sends `PAYMENT_BOOKED`. It does all of this in one transaction. The
  unique index on `bunq_payment_id` lets a bank payment book at most one request.
- Every directory that decides by the clock takes an `InstantSource`.
- A switch into `SMP` from before the season stamps `fresh_start`. smp starts its own track over once
  per stamp.
- A run is a row of `steward_inbox`, and a unique index allows one open run. A kind that names more
  than its services has a table of its own, such as `steward_restore`, and the services are rows of
  `run_service`. The proxy reads the run's countdown from `scheduled_for` and `countdown_end`, and
  the services it moves players off from `run_service`.

## Roles and migrations

The `migrate` service of steward-agent's image migrates as the owner of every table. Every other
service logs in as its own `DatabaseRole`. V1 grants each role the tables it owns and the tables of
others that it reads or writes. The shared read models go through one read role.

V1 names the roles by Flyway placeholder and creates none. `DatabaseRole.provision` creates them
first, with the passwords from its environment. `DatabaseRoleIntegrationTest` checks the grants.

A migration never drops, renames or narrows what the previous release reads or writes. It never adds
a required column without a default to a table the previous release inserts into. The proxy's and
limbo's standbys run the previous release during an update, after `migrate` has run. The old shape
goes one release after the code stopped using it.

`MigrationsExpandBeforeTheyContractIntegrationTest` migrates to the installed release's newest
migration and checks the standbys' grants after the rest. A release that bumps `version` adds its
newest migration to the test's list. A pushed migration never changes (`MigrationsAreImmutableTest`).

## Tests

Tests reach PostgreSQL only through `TestDatabase` from the test fixtures. It starts one container
per test JVM. `fresh()` returns a new migrated database and `empty()` an unmigrated one.
`dataSourceAs(role)` logs in as a service. The image is the default of `compose.yml`, which
`TestDatabaseImageTest` checks.
