# database

Everything that reads or writes PostgreSQL: access and links, the season phase, who is online,
the audit log, payments, update runs, the inboxes, the signal hub and the
migrations under `src/main/resources/db/migration`. JDBI, HikariCP and the driver are
`compileOnly`, so a consumer brings the runtime it already has; this module never migrates.

- **Access** is read through `AccessReader` and written through `AccessDirectory`, which extends it.
  The plugins and the proxy's router only read.
- **A refused write** throws `Refused` with a typed reason (`UpdateRefusal`, `SeasonDateRefusal`)
  and a message from this module's own bundle, `messages/database`. `DatabaseText` renders it in
  English for Steward and the logs.
- **Signals**: a process opens one `SignalHub`, the only `LISTEN` connection it holds, and registers a
  refresh per `Channel`. Every refresh runs on connect, on every signal and once a minute, whatever the
  channel, so a lost notification costs a minute and a reconnect re-reads in full; this is the only polling
  left for database state. Work that takes long rings a `Doorbell` for its own thread. `Channel` names who
  emits and who listens on each.
- **Inboxes**: a request from one process to another is a row in its consumer's inbox table, and `Inbox` is the one
  implementation over every such table: submit, claim with `SKIP LOCKED`, progress, settle, expire, cancel, each
  announced on the table's channel. A kind is a record of the consumer's sealed payload type, stored as JSON, so no
  kind carries a command line; `scheduled_for` lets any request wait for its time. The answer is an `Outcome`: done,
  refused with a `Refusal`, or failed, and a handler that throws fails its request, never retried. An asker needs
  no write to see its request expire: a pending row past its patience reads as expired. `Inboxes` lists every table.
- **The servers' inboxes** take a few typed kinds each: `Reload` (one record every server takes), the SMP's track
  actions and the Hunger Games start. A server answers one the same way its console answers the same action, and a
  refusal of one is a `ServerRefusal` worded in this module's bundle, so Steward can say it without the server's.
- **Time** comes from the caller: every directory that decides by the clock takes an `InstantSource`.
- **Who asked** for a request is an `Actor`: a person by Discord id, Steward on its own, or the host's
  installer, stored as `actor_kind` and `actor_id` in every request table and never as a name to parse.
- **The season phase** is one row every process follows on its hub. A switch into `SMP` from before the season stamps
  `fresh_start`, and smp starts its own track over once per stamp whenever it next sees the phase, so a server that
  was down at the switch still starts over and no other process writes smp's tables.
- **A run** is a request in the worker's inbox, `worker_inbox`; a unique index keeps one open at a time.
- **A run's countdown** is typed columns the proxy reads: `scheduled_for` (when the worker may claim it),
  `countdown_end` (when the servers go down) and `moving` (what they are), never the report JSON.

**Roles**: steward-worker migrates and owns every table; every other service logs in as its own
`DatabaseRole`, and V1 grants each what it owns and what it reads or writes of someone else's, the
shared read models through one read role. V1 names the roles by Flyway placeholder and never creates
one: roles belong to the cluster, so the migrator creates them first (`DatabaseRole.provision`), with
the passwords its environment carries. `DatabaseRoleIntegrationTest` holds the grants.

The test fixtures publish `TestDatabase`, the one way a test reaches PostgreSQL: one container per
test JVM, with the roles created and migrated once, and a new database cloned from it for every
`fresh()` (or an unmigrated one from `empty()`); `dataSourceAs(role)` logs in as a service would. Its image is compose.yml's default, which `TestDatabaseImageTest` holds.
