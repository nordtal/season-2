# discord-bot — running it

The one season 2 process with no Minecraft dependency. At runtime it talks to **PostgreSQL and the
Discord gateway** and to nothing else, so it can be deployed and operated long before the proxy or
any Paper backend exists.

**It has no bunq key and no bunq SDK** (steward/109). Buying access still works exactly as it did
from a member's side; what changed is who makes the call. The bot writes a row asking for a payment
link, `steward-worker` — the only container in the network that holds a bank credential — creates the
bunq.me tab and writes the link back, and `nordtal_payment` wakes the bot so the waiting message
fills itself in. Money that arrives is found by the worker and *booked* here: the tier, the grant,
the role, the DM and the public thank-you are all Discord's business and stayed.

It does not apply the schema — the `steward-worker` container does, because a release that adds a table is a
release that adds a migration and the two belong to one owner. The bot runs Flyway's `validate()` at
startup and refuses to start on a mismatch, naming the command to run. So an empty database is not
enough; the worker has to have run first:

```bash
docker compose run --rm steward-worker migrate
```

This file is only about starting the container; the deployment as a whole is
[../deploy/README.md](../deploy/README.md).

## What you need first

- A **Discord application** with the `GUILD_MEMBERS` privileged intent enabled. Without it there is
  no member cache, and both reconciles read that cache.
- The **guild id, four role ids and the admin channel id**, plus a role and two channel ids per
  language. None has a usable default — the bot refuses to start until they are real.
- Docker with the compose plugin.
- If access is to be **sold**: a bunq API key and monetary account id, on the `steward-worker`
  service as `NORDTAL_STEWARD_BUNQ_API_KEY` / `NORDTAL_STEWARD_BUNQ_ACCOUNT_ID`. Nothing here reads
  them. A deployment without them runs perfectly and sells nothing; `steward-worker` says which of
  the two it is in one line at startup.

## Configuration is environment variables only

`compose.yml` carries no values. Every setting in `bot.yml`, `database.yml` and `access.yml` arrives
as an environment variable; jcore applies the environment *after* writing the file and *before*
validating, so a container that has never seen a config file starts correctly from the environment
alone, and an overridden value is never written back into the config volume.

The variable name is the setting's path with `.` and `-` turned into `_`, prefixed by the file's
namespace — `NORDTAL_DATABASE_*`, `NORDTAL_BOT_*`, `NORDTAL_ACCESS_*`. **An empty value counts as
unset**, so every optional variable in `.env.example` may stay empty.

Two settings are lists and take **JSON**:

```
NORDTAL_ACCESS_LANGUAGES=[{"tag":"en","role":"…","contribution-channel":"…","link-channel":"…","hunger-games-channel":"…"}]
NORDTAL_ACCESS_TIERS=[{"days":30,"price-cents":300}]
```

The keys inside are config keys, not Java method names. **A key the spec does not know is silently
ignored inside JSON** — the strict unknown-key check runs against the YAML tree, not an environment
value — so a typo there means the default is used with no warning. Every load prints which settings
the environment overrode; read the startup log.

## Start it

The bot is the `bot` profile in the one stack at the repository root, and that profile can be brought
up alone. From the installation directory on the host:

```bash
./nordtal.sh                                        # writes the environment file, once
COMPOSE_PROFILES=db,bot docker compose --env-file /etc/nordtal/season-2.env up -d
```

The first line is `deploy/nordtal.sh`, which installs itself into the directory it is run in; a
host that has never had it runs the `curl` line in the project [README](../README.md).

**No Gradle step and no `--build`.** The image is pulled from `ghcr.io/nordtal/discord-bot:latest`,
pushed by `release.yml` when a release is published. There is no tag to choose: `IMAGE_TAG` was
removed on 2026-09-09. That is not a convenience — a deploy pulls and never builds, so an
image existing only on one host fails a deploy.

To build it here instead, which is what the `build:` block is for:

```bash
./gradlew :discord-bot:shadowJar
COMPOSE_PROFILES=db,bot docker compose up -d --build
```

The image only copies a finished jar and Compose cannot run Gradle, so the jar has to exist first.
Nothing names a version: the Dockerfile globs `build/libs` and takes whatever Gradle just produced,
so a local build cannot pick up a jar from an older run of it. `BOT_VERSION` did name it until
2026-09-09 and had been saying 0.2.3 against a repository on 0.8.1.

**The image tag is a floor, not the version.** The bot runs whatever `discord-bot-*.jar` is in the
`bot-jar` volume, which steward-worker fills exactly as it fills every `plugins/` folder — so the bot
moves by the same mechanism as every other module. It does not roll *back* by any mechanism: nothing
pins a release any more, and the way out of a bad one is to publish a better one. The jar baked into
the image is used only while that volume is empty, which is a first deployment and nothing else, and
reading it as "what is running" is wrong: the entrypoint prints the jar it picked on every start, and
`docker compose run --rm steward-worker report` says what is installed.

`COMPOSE_PROFILES` decides what comes up: `bot` alone against an existing database, `db,bot` with a
PostgreSQL beside it, `db,bot,mc` for the full network. The `steward-worker` service has no profile and comes
up with all of them — it is the only process that applies the schema, and the bot would otherwise
crash-loop on its schema check.

There is deliberately **no `depends_on` on the database**: Compose implicitly enables a dependency's
profile, which would start a PostgreSQL even when the deployment does not want one. The bot exits when
the database is unreachable and the restart policy brings it back, so a first boot may log one
connection failure. The `depends_on` on `steward-worker` is safe for the same reason reversed — the worker
has no profile, so depending on it cannot drag in a service nobody asked for.

## In production

The same compose file. What changes is `.env`:

- Nothing is built — `docker compose pull` first.
- `POSTGRES_BIND` stays on `127.0.0.1`. The proxy and the plugins reach the database over the compose
  network; the published port exists only for backups and a `psql` from the host.
- The bunq credentials are not here at all any more. They belong to `steward-worker`, together with
  the `bunq-context` volume; see [../steward-worker/README.md](../steward-worker/README.md).

## Things that bite

- **`NORDTAL_ACCESS_PAYMENT_WATERMARK` and `NORDTAL_ACCESS_PAYMENT_RECENT_PAYMENT_COUNT` are gone**
  (steward/109). They are `NORDTAL_STEWARD_BUNQ_WATERMARK` and
  `NORDTAL_STEWARD_BUNQ_RECENT_PAYMENT_COUNT` on the worker. The watermark is the *same row* in
  `bot_setting`, so a deployment that has already stamped one keeps it across the move. A file that
  still carries the old names loses those two lines with a WARN and a `.bak`, and the bot starts.
- **`payment.request-ttl-hours` deliberately did not move.** The bot writes the `payment_request`
  row, so that number becomes `expires` and is the same one the buyer is told the link is good for.
  The worker expires rows by reading the column, never the setting.
- **"Your payment link is being created" has three exits**, and all three are the bot's: the link,
  the refusal (`tab_failed`), and — after ten minutes — a line naming the reference. An ephemeral
  message cannot outlive this process, so a bot restarted mid-purchase leaves that message on its
  last sentence; the request, its reference and its tab are all still in the table.
- **The message publish and the role reconcile touch real channels and roles** the moment the ids
  point at a real guild. Against the production guild, point the language channels at admin-only
  channels first.
- **A crash loop usually means a config error, not a flaky start.** The bot fails fast by design and
  the log line names the file and the setting.

## What cannot be tested without the network

- **Link redemption end to end** — `link_code` rows are only written by the proxy's login gate.
- **That a grant actually lets somebody in** — grants are rows; only the proxy enforces them.
- **`/phase`** — it writes the phase, but needs a plugin listening.

- **Anything that needs a bunq answer** — the link arriving, a refused tab, money being matched. The
  bot's half of all three is a row it reads; producing that row needs `steward-worker` and a real
  account.

Everything else — the purchase flow, settlement, the tier rules, roles, the reconciles, expiry DMs
and the admin log — is exercisable with the bot alone.
