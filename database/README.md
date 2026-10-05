# database

Everything that reads or writes PostgreSQL: access and links, the season phase, who is online,
the audit log, payments, update runs, the inboxes, the signal hub and the
migrations under `src/main/resources/db/migration`. JDBI, HikariCP and the driver are
`compileOnly`, so a consumer brings the runtime it already has; this module never migrates.

- **Access** is read through `AccessReader` and written through `AccessDirectory`, which extends it.
  The plugins and the proxy's router only read.
- **A player's card** is the one hover every name carries in game: `PlayerCard` builds it from what a process holds
  of the player (a server's `PlayerIdentity`, the proxy's login roster, which `AccessState` fills at login) as a
  message of this module's bundle: their role, the crest `Prestige` derives from their play time, and the play time
  itself. It lives here because every Paper server and the proxy hold that record and load this bundle, and the
  crest table is the network's setting.
- **A refused write** throws `Refused` with a typed reason (`UpdateRefusal`, `SeasonDateRefusal`)
  and a message from this module's own bundle, `messages/database`. Steward words it with the admins'
  overrides; `DatabaseText` renders it, or a message of the admin bundle, in packaged English for a log line.
- **Signals**: a process opens one `SignalHub`, the only `LISTEN` connection it holds, and registers a
  refresh per `Channel`. Every refresh runs on connect, on every signal and once a minute, whatever the
  channel, so a lost notification costs a minute and a reconnect re-reads in full; this is the only polling
  left for database state. Work that takes long rings a `Doorbell` for its own thread. `Channel` names who
  emits and who listens on each.
- **Game data**: `GameDataStore` holds each Paper server's `GameCatalogue` and the icons steward-agent
  drew per Minecraft version; `GameCatalogue.union` is the one merge of several servers' catalogues.
- **Command trees**: `CommandTreeStore` holds each server's `CommandTree`, its Brigadier dispatcher flattened into
  indexed nodes, a shared node and a redirect one index each. `CommandTree.of` is the one walk; Paper and Velocity
  hand it their nodes through a `Shape`, since neither platform's Brigadier reaches this module, and
  `CommandTreeWriter` writes a tree only when it differs from the last one written. A server reads only the names
  of the rows, which its upsert needs, and steward reads the trees.
- **Registration** is discord-bot's: a round per `Game`, named by its key, with its teams and members. The game's
  server only reads it and moves the round's `RegistrationState`: closed when a game starts, open again when a
  restart aborts the game, ended once one is decided. What a game writes is its own (`hg_game`, `hg_event`,
  `hg_team_colour`, `hg_ready`).
- **Inboxes**: a request from one process to another is a row in its consumer's inbox table, and `Inbox` is the one
  implementation over every such table: submit, claim with `SKIP LOCKED`, progress, settle, expire, cancel, each
  announced on the table's channel. A kind is a record of the consumer's sealed payload type, stored as JSON, so no
  kind carries a command line; `scheduled_for` lets any request wait for its time. The answer is an `Outcome`: done,
  refused with a `Refusal`, or failed, and a handler that throws fails its request, never retried. An asker needs
  no write to see its request expire: a pending row past its patience reads as expired. `Inboxes` lists every table.
- **The servers' inboxes** take a few typed kinds each: the SMP's track actions, the Hunger Games start and a preview. No
  server is asked to reload, since settings and message overrides reach every process on the signal hub, so the
  waiting room and the proxy have no inbox. A server answers one the same way its console answers the same action, and a
  refusal of one is a `ServerRefusal` worded in this module's bundle, so Steward can say it without the server's.
- **A preview** is the one kind every consumer of a text shares: `PREVIEW_MESSAGE` in the SMP's, the Hunger Games
  server's and the bot's inbox, each carrying a `MessagePreview`, the text an admin is trying for one key with its
  typed example values, where the key is shown and the colours of the key's own service. It shows the text to that
  admin alone and saves nothing, so it has no journal line; a server refuses it with `NOT_HERE` once the player has
  left, the bot with `NOT_DELIVERED` when Discord delivers no direct message.
- **A payment is booked** by `Bookings`, in one transaction over the locked request: paid, the access it buys
  appended through `Grants`, the donor flag, the journal line and the bot's `PAYMENT_BOOKED`, all or nothing. A
  bank payment books at most one request, which the unique index on `bunq_payment_id` decides; `Tiers` is the
  rule for what an amount buys.
- **Time** comes from the caller: every directory that decides by the clock takes an `InstantSource`.
- **Who asked** for a request is `:common`'s `Actor`: a person by Discord id, Steward on its own, or the host's
  installer, stored as `actor_kind` and `actor_id` in every table that records who asked (the request tables, the
  journal and `service_plugin`) and never as a name to parse.
- **The journal**, `audit_log`, is written only through `Journal.write`, inside the caller's transaction where there is
  one, the phase and date lines included. An `AuditLine` is a `JournalAction`, an `Actor` like a request's, the person
  it concerns and the line as a message of `AdminTexts`, its values typed; the row stores the message in `MessageJson`'s
  shape and never a sentence, so Steward's page and the bot's admin channel each render it through their own target.
  `AdminTexts` is the admin bundle, `messages/admin`, English only: the journal, the words for a run and the alerts,
  which Steward and the bot share. A value keeps the name of the fact it states; a line without typed values keeps its words as
  `journal.written`.
- **An alert**, a row of `admin_alert`, is told in the same bundle: its title a message and the lines below it a list
  of them, so a lock screen, the admin channel and Steward's page render one row three ways. Its subject stays a
  name, which tells two alerts of a type apart. A row or a bot post without typed words keeps them as `alert.words`.
  `DatabaseJson` is the kernel's codec with a message typed, and the one that writes a message into a row, the
  journal's, an alert's or an inbox payload; Steward's API is built on it.
- **An announcement** is one message per language of this module's bundle, its `announcement` section, which the
  bot renders in that language and posts into that language's channel. The SMP writes a milestone, its name in each
  language; Steward writes an admin's words, declared `PLAIN` so the markdown in them stays theirs. Rendering at the
  bot means an override of the text reaches a request already written. A request that carries finished text keeps each language's as `announcement.words`.
- **The season phase** is one row every process follows on its hub. A switch into `SMP` from before the season stamps
  `fresh_start`, and smp starts its own track over once per stamp whenever it next sees the phase, so a server that
  was down at the switch still starts over and no other process writes smp's tables.
- **A run** is a request in the run inbox, `steward_inbox`; a unique index keeps one open at a time. Its outcome is
  always an `UpdateReport`, stored through `DatabaseJson`: each note, each line's detail and each change no
  version names a message of the bundle's `report` section, its values typed, so Steward's page and the bot each render it and
  `UpdateReports.english` prints it for the host. A report or a bare reason without typed values keeps its words as
  `report.words`.
- **A run's countdown** is typed columns the proxy reads: `scheduled_for` (when steward-agent may claim it),
  `countdown_end` (when the servers go down) and `moving` (what they are), never the report JSON.

**Roles**: the `migrate` service, from steward-agent's image, migrates as the owner of every table; every other service logs in as its own
`DatabaseRole`, and V1 grants each what it owns and what it reads or writes of someone else's, the
shared read models through one read role. V1 names the roles by Flyway placeholder and never creates
one: roles belong to the cluster, so the migrator creates them first (`DatabaseRole.provision`), with
the passwords its environment carries. `DatabaseRoleIntegrationTest` holds the grants.

**Migrations expand before they contract.** An update runs `migrate` while the proxy's and limbo's standbys
still run the previous release, so a migration never drops, renames or narrows what that release reads or
writes, and never adds a required column without a default to a table it inserts into. The new shape comes
first; the old one goes a release after the code stopped using it. `MigrationsExpandBeforeTheyContractIntegrationTest`
migrates to the installed release's newest migration, takes the proxy's and limbo's grants as what they use, and
holds them after the rest: a release adds its newest migration to the test's list when it bumps `version`, and a
grant its code no longer uses is listed there before a later release takes it away. A pushed migration never
changes, which `MigrationsAreImmutableTest` holds.

The test fixtures publish `TestDatabase`, the one way a test reaches PostgreSQL: one container per
test JVM, with the roles created and migrated once, and a new database cloned from it for every
`fresh()` (or an unmigrated one from `empty()`); `dataSourceAs(role)` logs in as a service would. Its image is compose.yml's default, which `TestDatabaseImageTest` holds.
