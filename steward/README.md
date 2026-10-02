# steward

Steward's web interface and its API, payments, metrics, alerts and the clocks that ask for runs.
It carries out no run itself: a button, a clock or `/update` writes a row into `steward_inbox`, and
`steward-agent` carries it out. It starts once `steward-agent` is healthy, which is when the schema
is current.

```bash
docker compose up -d steward                                           # serve
docker exec nordtal-s2-steward-1 steward forget-factors <discord-id>   # a lost security key
docker run --rm ghcr.io/nordtal/steward generate-vapid-keys            # a Web Push keypair
```

Any other word is refused, so a typo never starts a second interface beside the running one.

## Why one process

The interface and the runs used to be two services, joined by an internal HTTP API with a shared
token, a proxy in the interface that forwarded every route to it, two configuration sets, two
database pools and two ways to follow a container's log. All of that existed only to carry calls
between two halves that serve the same admin. Now the stack routes (`api/Routes`) sit on the same
Javalin as the rest and pass the same gates: a read needs a signed-in admin with a key
(`KEY_HELD`), a change a fresh one (`KEY_FRESH`). The plugin "added by" comes from the session, and a
long log follow re-checks the session once a second, so a sign-out ends it.

The process on the internet holds no Docker socket and mounts no volume but the last installation's
settings files, which its first start imports.
Everything Docker knows comes from `steward-agent` through `AgentClient`: the containers and their
last sample, logs, the console, image drift, the host's numbers, the archives and the plan of the
next update. The managed plugins' list, search and add are passed through to the agent unchanged.

It logs in as `nordtal_steward` (`DatabaseRole.STEWARD`), never as the owner. Its tests open the
database under that role too, so a statement steward was never granted fails in `check`.

## Where a version comes from

| what                                                 | source                                                     |
| ---------------------------------------------------- | ---------------------------------------------------------- |
| the season-2 jars, the resource pack and its `.sha1` | GitHub releases, `nordtal/season-2`                        |
| DisplayTags                                          | GitHub releases, `nordtal/papermc-display-tags`            |
| PacketEvents                                         | Modrinth v2, filtered to the Minecraft version and `paper` |
| Paper, Velocity                                      | PaperMC Fill v3, newest `STABLE` build                     |
| what is installed                                    | the volumes under `volumes-root`                           |
| what pack the proxy offers                           | the proxy's `pack` settings, `url` and `sha1`              |

Every repository is read through `/releases/latest`, which skips drafts and pre-releases. There is no
pin and no rollback: a bad release is corrected by publishing a better one.

## What it does

- **Runs.** Every button that stops something (update, restart, backup, down, start, recreate,
  deploy, restore, removing a plugin) writes a row into `steward_inbox`; so do the two clocks.
  `steward-agent` carries it out and writes the report back into the row, which the interface follows
  on `nordtal_update`. steward is restarted by a run like any other service.
- **Containers.** It asks `steward-agent` for state, health and the last sample, and to write into
  the Minecraft consoles, naming who typed the line. It never touches the socket.
- **Images.** It shows each service's image against the registry, and an image it could not check
  as unchecked, never as current.
- **Metrics.** Every 30 seconds it copies the agent's new sampler rounds into `metric_sample`, and
  folds them into hourly means after 30 days.
- **Backups.** The nightly clock only writes a request row; `steward-agent` runs `pg_dump` inside
  the postgres container and writes the volumes as zstd tars. steward lists and downloads them
  through the agent. There is no offsite copy.
- **The journal.** Every route that changes something writes its line through `:database`'s `Journal` with the
  signed-in admin as a structured actor (`DiscordAuth.Account.actor()`, `Sessions.Session.ownLine` for one's own
  keys and browsers) and typed facts. The Access page draws the actor as a profile and the facts by key.
- **Alerts.** Every alert is a row in `admin_alert`, raised by whoever saw it: steward measures the
  stack every 30 seconds against the `web` group's thresholds and raises a failed run and a payment
  nobody can book, once per bank payment; the bot raises what it could not do in Discord or with a purchase. Steward routes each row once, to Web Push and to the admin channel through the
  bot's inbox, per alert type and per admin. The browser only displays `GET /api/alerts`.
- **Payments.** It books them and holds no bank credential. A booking is one transaction in
  `:database`'s `Bookings`: the request is paid, the access appended, the donor flag set, the journal
  line written and the bot told through its inbox, or none of it. The poll books what it matched at the
  network's `prices` as they were at start; a booking by hand from the Access page is the same booking
  at what was ordered, answered at once, with the admin as its actor. Every question to bunq goes to
  `steward-bunq` (`steward#bunq`, its address and the token they share). At start it asks
  `steward-bunq` for the account for up to thirty seconds. When it answers, the poll starts and the
  bot learns whether payments are on; when it does not, the log says so at ERROR and payments stay
  off until the next start.

## Configuration

The connection and every secret come from the environment; everything else is three groups in the
database, `steward`, `web` and `alerts`, which Steward publishes at start and edits on its settings
pages.

| group      | environment                  | holds                                                             |
| ---------- | ---------------------------- | ----------------------------------------------------------------- |
| `steward`  | `NORDTAL_STEWARD_*`          | the clocks, the agent's and steward-bunq's addresses              |
| `web`      | `NORDTAL_STEWARD_WEB_*`      | the port, the public address, Discord sign-in, WebAuthn, Web Push |
| `alerts`   | `NORDTAL_STEWARD_ALERTS_*`   | the thresholds steward's measured alerts fire on                  |
| `database` | `NORDTAL_STEWARD_DATABASE_*` | the connection, from the environment alone                        |

`StewardSettings` loads and checks them before the web starts, so a wrong address or port stops the
start rather than the first sign-in. A change to `steward` re-arms both clocks without a restart,
and one to `alerts` applies at the next reading. The first start finds the last installation's
`steward.yml` and `web.yml` in `steward-config`, imports what differs from the defaults and deletes
them.

Where a run's versions come from is `steward-agent`'s `runs` group.

## Tests

`./gradlew :steward:test` needs no network; its stack tests talk to `AgentStandIn`, the agent's API
over a fake daemon, through the real client.
