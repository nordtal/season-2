# database

Everything that reads or writes PostgreSQL: access and links, the season phase, who is online, the
audit log, payments, update runs, the inboxes, the signal hub and the migrations under
`src/main/resources/db/migration`. JDBI, HikariCP and the driver are `compileOnly`, so a consumer
brings the runtime it already has; this module never migrates.

| piece          | what it is                                                                                                              |
| -------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Access         | read through `AccessReader`, written through `AccessDirectory`; plugins and the proxy only read                         |
| Player card    | `PlayerCard` builds the hover every name carries (role, `Prestige` crest, play time) from a process's `PlayerIdentity`  |
| Refused writes | `Refused` with a typed reason (`UpdateRefusal`, `SeasonDateRefusal`) and a message of the `messages/database` bundle    |
| Signals        | one `SignalHub` per process, the only `LISTEN` connection, one refresh per `Channel`                                    |
| Game data      | `GameDataStore`: each server's `GameCatalogue` and the icons drawn per Minecraft version; `GameCatalogue.union` merges  |
| Command trees  | `CommandTreeStore`: each server's `CommandTree` flattened into indexed nodes; `CommandTree.of` is the one walk          |
| Registration   | discord-bot's: a round per `Game` with its teams; the game server moves the round's `RegistrationState`                 |
| Inboxes        | `Inbox` over every consumer's table (`Inboxes` lists them); a kind is a record of a sealed payload type, stored as JSON |
| Bookings       | `Bookings` books a payment in one transaction; `Tiers` is the rule for what an amount buys                              |
| Journal        | `Journal.write` is the only writer of `audit_log`; an `AuditLine` is a message of `AdminTexts`                          |
| Alerts         | a row of `admin_alert`, told in the admin bundle: a title message and a list of messages below it                       |
| Announcements  | one message per language of the bundle's `announcement` section, rendered by the bot in each channel's language         |
| Season phase   | one row every process follows on its hub                                                                                |
| Runs           | a request in `steward_inbox`, one open at a time by a unique index; its outcome is an `UpdateReport`                    |

## Conventions

- **Signals.** Every refresh runs on connect, on every signal and once a minute, so a lost
  notification costs a minute and a reconnect re-reads in full. Long work rings a `Doorbell` for its
  own thread; `Channel` names who emits and who listens.
- **Inboxes.** Submit, claim with `SKIP LOCKED`, progress, settle, expire and cancel are each announced
  on the table's channel. The answer is an `Outcome`: done, refused with a `Refusal`, or failed; a
  handler that throws fails its request, never retried. A pending row past its patience reads as
  expired. `scheduled_for` lets any request wait for its time. Servers take a few typed kinds each
  (the SMP's track actions, the Hunger Games start, a preview); settings and overrides reach every
  process on the hub, so no server is asked to reload.
- **Previews.** `PREVIEW_MESSAGE` is the one kind every consumer of a text shares: a `MessagePreview`
  with the typed example values, where the key is shown and the key's own service's colours. It shows
  to that admin alone and writes no journal line; a server refuses with `NOT_HERE` once the player
  left, the bot with `NOT_DELIVERED`.
- **Messages in rows.** A row stores a message in `MessageJson`'s shape, never a sentence, written by
  `DatabaseJson`; the journal, alerts and run reports each render through the reader's own target.
  A value without typed words keeps them as `journal.written`, `alert.words`, `report.words` or `announcement.words`.
- **Who asked** is `:common`'s `Actor` (a person by Discord id, Steward, or the host's installer),
  stored as `actor_kind` and `actor_id` and never as a name.
- **Bookings.** Paid, the access appended through `Grants`, the donor flag, the journal line and the
  bot's `PAYMENT_BOOKED`: all or nothing. A bank payment books at most one request, which the unique
  index on `bunq_payment_id` decides.
- **Time** comes from the caller: every directory that decides by the clock takes an `InstantSource`.
- **The season phase.** A switch into `SMP` from before the season stamps `fresh_start`, and smp
  starts its own track over once per stamp whenever it next sees the phase.
- **A run's countdown** is typed columns the proxy reads: `scheduled_for`, `countdown_end` and
  `moving`, never the report JSON.

## Roles and migrations

The `migrate` service, from steward-agent's image, migrates as the owner of every table. Every other
service logs in as its own `DatabaseRole`, and V1 grants each what it owns and what it reads or writes
of someone else's, the shared read models through one read role. V1 names the roles by Flyway
placeholder and never creates one: the migrator creates them first (`DatabaseRole.provision`) with the
passwords its environment carries. `DatabaseRoleIntegrationTest` holds the grants.

**Migrations expand before they contract.** An update runs `migrate` while the proxy's and limbo's
standbys still run the previous release, so a migration never drops, renames or narrows what that
release reads or writes, and never adds a required column without a default to a table it inserts
into. The old shape goes a release after the code stopped using it.
`MigrationsExpandBeforeTheyContractIntegrationTest` migrates to the installed release's newest
migration and holds the standbys' grants after the rest: a release adds its newest migration to the
test's list when it bumps `version`. A pushed migration never changes (`MigrationsAreImmutableTest`).

The test fixtures publish `TestDatabase`, the one way a test reaches PostgreSQL: one container per
test JVM with the roles created and migrated once, a new database cloned for every `fresh()` (or an
unmigrated one from `empty()`), and `dataSourceAs(role)` to log in as a service would. Its image is
`compose.yml`'s default, which `TestDatabaseImageTest` holds.
