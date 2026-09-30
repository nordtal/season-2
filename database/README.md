# database

Everything that reads or writes PostgreSQL: access and links, the season phase, who is online,
the audit log, payments, update runs, the request inboxes, the `LISTEN`/`NOTIFY` loop and the
migrations under `src/main/resources/db/migration`. JDBI, HikariCP and the driver are
`compileOnly`, so a consumer brings the runtime it already has; this module never migrates.

- **Access** is read through `AccessReader` and written through `AccessDirectory`, which extends it.
  The plugins and the proxy's router only read.
- **A refused write** throws `Refused` with a typed reason (`UpdateRefusal`, `SeasonDateRefusal`)
  and a message from this module's own bundle, `messages/database`. `DatabaseText` renders it in
  English for Steward and the logs.
- **Time** comes from the caller: every directory that decides by the clock takes an `InstantSource`.

The test fixtures publish `TestDatabase`, the one way a test reaches PostgreSQL: one container per
test JVM, migrated once, and a new database cloned from it for every `fresh()` (or an unmigrated one
from `empty()`). Its image is compose.yml's default, which `TestDatabaseImageTest` holds.
