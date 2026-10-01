# discord-bot

The season 2 Discord bot: access purchases and grants, account linking, roles, the Hunger Games
registration, status channels and the admin log. It talks to PostgreSQL and the Discord gateway and
to nothing else, so it runs without any Minecraft server.

It holds no bunq key. The bot writes a `payment_request` row; `steward` has `steward-bunq` create
the bunq.me tab, writes the link back and finds the money, and the bot books it.

It never migrates the schema. At startup it runs Flyway's `validate()` and refuses a database it
was not built against, so `steward` has to have run first:

```bash
docker compose run --rm steward migrate
```

## What it needs

- A Discord application with the `GUILD_MEMBERS` privileged intent.
- The guild id, four role ids, the admin channel id, and per language a role and its channel ids.
  None has a usable default; the bot refuses to start until they are set.

## Configuration

`access.yml` is edited in Steward. `compose.yml` passes the bot only its token, the database and
the two ids `deploy/nordtal.sh` asks for, `NORDTAL_ACCESS_GUILD_ID` and `NORDTAL_ACCESS_ROLES_ADMIN`;
those win over the file. The startup log lists every setting the environment overrode.

## Run it

The bot is the `bot` profile of the stack at the repository root. From the installation directory:

```bash
COMPOSE_PROFILES=db,bot docker compose --env-file /etc/nordtal/season-2.env up -d
```

To build the image locally, build the jar first; the Dockerfile copies whatever `build/libs` holds:

```bash
./gradlew :discord-bot:shadowJar
COMPOSE_PROFILES=db,bot docker compose up -d --build
```

The container runs the newest `discord-bot-*.jar` in its jar volume, which `steward` fills,
and falls back to the jar baked into the image only while that volume is empty. The first log line
names the jar it picked. The whole deployment is described in [../deploy/README.md](../deploy/README.md).

## Where things live

- `access/`: purchases, grants, linking, tiers and the managed messages.
- `hungergames/`: team registration.
- `status/`: the status channel names.
- `announce/`, `discord/`: announcements, admin commands and the update feed.
- `config/`: the three config specs and their defaults.
- `src/main/resources/messages/`: the translations.
