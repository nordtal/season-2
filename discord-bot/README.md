# discord-bot — running it

The bot is the one season 2 process that has no Minecraft dependency at all. At runtime it talks to
**PostgreSQL, the Discord gateway and bunq**, and to nothing else — so it can be deployed and
operated long before the proxy or any Paper backend exists.

**It applied the database schema until 2026-09-01 and does not any more.** The `updater` container
does ([../docs/updater.md](../docs/updater.md)), because a release that adds a table is a release
that adds a migration and the two belong to one owner. What the bot does instead is *check*: Flyway's
own `validate()` at startup, comparing the migrations in its jar against what the database says has
been applied, and a refusal to start on a mismatch that names the command to run. So an empty
database is no longer all it needs — the updater has to have run first:

```bash
docker compose run --rm updater migrate
```

The concept is [../docs/access-system.md](../docs/access-system.md); how the season deploys as a
whole is [../deploy/README.md](../deploy/README.md). This file is only about starting the
container.

## What you need first

- A **Discord application** with the `GUILD_MEMBERS` privileged intent enabled in the developer
  portal. Without it there is no member cache, and both reconciles read that cache.
- The **guild id, four role ids and the admin channel id**, plus a role and two channel ids per
  language. None of these has a usable default — the bot refuses to start until they are real.
- A **bunq API key** and the numeric monetary account id, for either `SANDBOX` or `PRODUCTION`.
- Docker with the compose plugin.

## Configuration is environment variables only

[`../compose.yml`](../compose.yml) carries no values. Every setting in `bot.yml`, `database.yml` and `access.yml`
arrives as an environment variable; jcore applies the environment *after* it writes the file and
*before* it validates, so a container that has never seen a config file starts correctly from the
environment alone, and **an overridden value is never written back into the config volume**.

The variable name is the setting's path with `.` and `-` turned into `_`, prefixed by the file's
namespace — `NORDTAL_DATABASE_*`, `NORDTAL_BOT_*`, `NORDTAL_ACCESS_*`. **An empty value counts as
unset**, so every optional variable in `.env.example` can stay empty.

Two settings are lists and take **JSON**:

```
NORDTAL_ACCESS_LANGUAGES=[{"tag":"en","role":"…","contribution-channel":"…","link-channel":"…","hunger-games-channel":"…"}]
NORDTAL_ACCESS_TIERS=[{"days":30,"price-cents":300}]
```

The keys inside the JSON are the config keys, not the Java method names. **A key the spec does not
know is silently ignored inside JSON** — jcore's strict unknown-key check runs against the YAML
tree, not against an environment value — so a typo there means the default is used with no warning.
Check the startup log: every load prints which settings the environment overrode.

## Start it

The bot no longer has a compose file of its own: since 2026-09-01 the whole deployment is one
stack in [`../deploy`](../deploy), and the bot is the `bot` profile inside it. It keeps every
property this document claims for it — no Minecraft dependency, no waiting for a proxy or a
backend — because that profile can be brought up alone. Run these from the **repository root**,
which is where `compose.yml` lives:

```bash
cp .env.example .env      # fill it in — .env is gitignored and must never be committed
COMPOSE_PROFILES=db,bot docker compose up -d
```

**No Gradle step, and no `--build`.** Since 2026-09-02 the image is pulled from
`ghcr.io/nordtal/discord-bot`, pushed by `release.yml` when a release is published; `IMAGE_TAG`
picks which one and defaults to `latest`. That is not a convenience — Arcane deploys by pulling and
never builds, so an image that exists only on one host fails a deploy outright.

**To build it here instead**, which is what the `build:` block in `compose.yml` is for:

```bash
./gradlew :discord-bot:shadowJar
COMPOSE_PROFILES=db,bot docker compose up -d --build
```

The image only copies a finished jar and Compose cannot run Gradle, so that jar has to exist first.
`BOT_VERSION` in `.env` names it and has to match `gradle.properties`; a mismatch fails on the
missing file rather than quietly shipping the wrong version. It no longer decides the image *tag* —
`IMAGE_TAG` does.

### Since 2026-09-01 the image tag is a floor, not the version

The bot runs whatever `discord-bot-*.jar` is in the `bot-jar` volume, which the `updater` container
fills the same way it fills every `plugins/` folder — so the bot moves and rolls back by the same
mechanism as the five other modules, which is the whole reason it stopped being an image-only
deployment. The jar baked into the image is used **only while that volume is empty**, which is a
first deployment and nothing else.

Two consequences worth stating:

- **`BOT_VERSION` going stale is harmless**, and reading it as "what is running" is wrong. The
  entrypoint prints the jar it picked on every start, and `docker compose run --rm updater` reports
  what is actually installed.
- **Updating the bot no longer needs a build or a pull.** `docker compose run --rm updater apply`,
  or the Install button on `/update` in Discord, puts the new jar in the volume; the restart picks
  it up.

A production host runs the released image instead and never builds:

```bash
docker compose pull && docker compose up -d
```

`up -d` without `--build` builds only when the image is not present locally — on a host without a
checkout that fails on the missing jar, which is the loud outcome rather than a surprising one.
`BOT_IMAGE` only needs a value to run an image that is not
`ghcr.io/nordtal/discord-bot:$BOT_VERSION`.

`COMPOSE_PROFILES` in `.env` decides what comes up. `bot` is the bot alone, against a database that
already exists; `db,bot` adds a PostgreSQL beside it; `db,bot,mc` is the production network. The
`updater` service has no profile and therefore comes up with all of them — it is the only process
that applies the schema, and the bot would otherwise crash-loop on `SchemaCheck` until somebody ran
it.

There is deliberately **no `depends_on` on the database**: recent Compose implicitly enables the
profile of a dependency, which would start a PostgreSQL even when the deployment does not want one.
The bot exits when the database is unreachable and the restart policy brings it back, so the first
boot may log one connection failure. There **is** a `depends_on` on the updater, and it is safe for
exactly the reason the database one is not: the updater has no profile, so depending on it cannot
drag in a service nobody asked for.

## The same file in production

The compose is meant to be the production one. What changes is `.env`:

- Nothing is built: `docker compose pull` first, and `BOT_VERSION` is the released tag — which,
  since the jar comes out of the volume, only matters until the first `updater apply`.
- `POSTGRES_BIND` stays on `127.0.0.1`. The proxy and the Paper plugins read the same database, but
  they are services in the same stack now and reach it over the compose network; the published port
  exists only for backups and for a `psql` from the host.
- `NORDTAL_BOT_BUNQ_ENVIRONMENT=PRODUCTION`, with the `bunq-context` volume emptied first — a bunq
  context file belongs to exactly one environment.

## Things that bite

- **The payment watermark stamps itself on the first start** and nothing ever rewrites it. Payments
  created before it are ignored forever. On a fresh database leave
  `NORDTAL_ACCESS_PAYMENT_WATERMARK` empty; setting it only overrides what is read, it does not
  replace what is stored. Starting a *test* bot against the database that will later be production
  therefore fixes the production watermark at that moment.
- **The bunq API context is registered from the host it is first used on.** The `bunq-context`
  volume must be created where the bot will actually run; do not copy a context file over from a
  laptop.
- **`ManagedMessages.publishAll()` and the role reconcile touch real channels and roles the moment
  the ids point at a real guild.** Against the production guild, point the language channels at
  admin-only channels first.
- **A crash loop with `restart: unless-stopped` usually means a config error, not a flaky start.**
  The bot fails fast by design and the log line names the file and the setting.

## What cannot be tested without the network

- **Link redemption end to end.** `link_code` rows are only ever written by `network-control`'s
  login gate, so without a proxy no code exists for `/link` to redeem. Insert a row by hand, or run
  a Velocity proxy with `network-control` and no backends.
- **That a grant actually lets somebody in.** Grants are database rows; only the proxy enforces
  them.
- **`/phase`.** It writes the phase, but no plugin is listening yet.

Everything else — the purchase flow, tab creation and cancellation, settlement, the tier rules,
roles, the reconciles, expiry DMs, the admin log and `/settle` — is exercisable with the bot alone.

## Verified 2026-08-31

Built from this tree with `docker compose up -d --build` and started with only environment
variables set and no config file anywhere: all three configs loaded (15 settings taken from the
environment), Flyway applied all five migrations to an empty PostgreSQL 17, and the process stopped
exactly where it should — on the deliberately invalid Discord token. *(That run predates the
migration moving to the updater; today the same run would refuse before the token, on an unmigrated
schema, and `SchemaCheckTest` covers both halves of that against a real PostgreSQL.)* bunq and Discord themselves are untested; see
[../docs/state-of-play.md](../docs/state-of-play.md#the-unverified-assumptions).

That run is also what found the `mergeServiceFiles()`/`duplicatesStrategy` bug in
`build-logic/src/main/kotlin/nordtal.shaded.gradle.kts`: until it was fixed, **the shaded bot jar
could not run its own migrations at all**, because Flyway's ServiceLoader registry had been
overwritten down to three entries. No test caught it — tests run against the ordinary runtime
classpath, never against the shaded artifact.
