# discord-bot — running it

The one season 2 process with no Minecraft dependency. At runtime it talks to **PostgreSQL, the
Discord gateway and bunq** and to nothing else, so it can be deployed and operated long before the
proxy or any Paper backend exists.

It does not apply the schema — the `updater` container does, because a release that adds a table is a
release that adds a migration and the two belong to one owner. The bot runs Flyway's `validate()` at
startup and refuses to start on a mismatch, naming the command to run. So an empty database is not
enough; the updater has to have run first:

```bash
docker compose run --rm updater migrate
```

This file is only about starting the container; the deployment as a whole is
[../deploy/README.md](../deploy/README.md).

## What you need first

- A **Discord application** with the `GUILD_MEMBERS` privileged intent enabled. Without it there is
  no member cache, and both reconciles read that cache.
- The **guild id, four role ids and the admin channel id**, plus a role and two channel ids per
  language. None has a usable default — the bot refuses to start until they are real.
- A **bunq API key** and the numeric monetary account id.
- Docker with the compose plugin.

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
up alone. From the repository root:

```bash
cp .env.example .env      # .env is gitignored and must never be committed
COMPOSE_PROFILES=db,bot docker compose up -d
```

**No Gradle step and no `--build`.** The image is pulled from `ghcr.io/nordtal/discord-bot`, pushed by
`release.yml` when a release is published; `IMAGE_TAG` picks which one. That is not a convenience —
Arcane deploys by pulling and never builds, so an image existing only on one host fails a deploy.

To build it here instead, which is what the `build:` block is for:

```bash
./gradlew :discord-bot:shadowJar
COMPOSE_PROFILES=db,bot docker compose up -d --build
```

The image only copies a finished jar and Compose cannot run Gradle, so the jar has to exist first.
`BOT_VERSION` names it and must match `gradle.properties`.

**The image tag is a floor, not the version.** The bot runs whatever `discord-bot-*.jar` is in the
`bot-jar` volume, which the updater fills exactly as it fills every `plugins/` folder — so the bot
moves and rolls back by the same mechanism as every other module. The jar baked into the image is used
only while that volume is empty, which is a first deployment and nothing else. `BOT_VERSION` going
stale is therefore harmless, and reading it as "what is running" is wrong: the entrypoint prints the
jar it picked on every start, and `docker compose run --rm updater report` says what is installed.

`COMPOSE_PROFILES` decides what comes up: `bot` alone against an existing database, `db,bot` with a
PostgreSQL beside it, `db,bot,mc` for the full network. The `updater` service has no profile and comes
up with all of them — it is the only process that applies the schema, and the bot would otherwise
crash-loop on its schema check.

There is deliberately **no `depends_on` on the database**: Compose implicitly enables a dependency's
profile, which would start a PostgreSQL even when the deployment does not want one. The bot exits when
the database is unreachable and the restart policy brings it back, so a first boot may log one
connection failure. The `depends_on` on the updater is safe for the same reason reversed — the updater
has no profile, so depending on it cannot drag in a service nobody asked for.

## In production

The same compose file. What changes is `.env`:

- Nothing is built — `docker compose pull` first.
- `POSTGRES_BIND` stays on `127.0.0.1`. The proxy and the plugins reach the database over the compose
  network; the published port exists only for backups and a `psql` from the host.
- `NORDTAL_BOT_BUNQ_ENVIRONMENT=PRODUCTION`, with the `bunq-context` volume emptied first — a bunq
  context file belongs to exactly one environment.

## Things that bite

- **The payment watermark stamps itself on the first start** and is never rewritten. Payments created
  before it are ignored forever. On a fresh database leave `NORDTAL_ACCESS_PAYMENT_WATERMARK` empty.
  Starting a *test* bot against what will later be the production database fixes the production
  watermark at that moment.
- **The bunq API context is registered from the host it is first used on.** Create the `bunq-context`
  volume where the bot will actually run; never copy a context file from a laptop.
- **The message publish and the role reconcile touch real channels and roles** the moment the ids
  point at a real guild. Against the production guild, point the language channels at admin-only
  channels first.
- **A crash loop usually means a config error, not a flaky start.** The bot fails fast by design and
  the log line names the file and the setting.

## What cannot be tested without the network

- **Link redemption end to end** — `link_code` rows are only written by the proxy's login gate.
- **That a grant actually lets somebody in** — grants are rows; only the proxy enforces them.
- **`/phase`** — it writes the phase, but needs a plugin listening.

Everything else — the purchase flow, tab creation and cancellation, settlement, the tier rules, roles,
the reconciles, expiry DMs and the admin log — is exercisable with the bot alone.
