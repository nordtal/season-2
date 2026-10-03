# discord-bot

The season 2 Discord bot: access purchases and grants, account linking, roles, the Hunger Games
registration, status channels and the admin log. It talks to PostgreSQL and the Discord gateway and
to nothing else, so it runs without any Minecraft server.

It holds no bunq key and books nothing. The bot writes a `payment_request` row for the wish to buy;
`steward` has `steward-bunq` create the bunq.me tab, writes the link back, finds the money and books it,
and the bot is told through its inbox (`PAYMENT_BOOKED`) and reacts: the role, the direct message, the
thank-you in the channel and the admin note. The prices are the network's `prices` group.

It never migrates the schema. At startup it runs Flyway's `validate()` and refuses a database it
was not built against; the `migrate` service migrates, and compose starts the bot only once that
has succeeded.

## What it needs

- A Discord application with the `GUILD_MEMBERS` privileged intent.
- The guild id, three role ids, the admin channel id, and per language a role and its channel ids.
  None has a usable default; the bot refuses to start until they are set.

## Configuration

the `access` group is edited in Steward. `compose.yml` passes the bot only its token, the database and
the two ids `deploy/nordtal.sh` asks for, `NORDTAL_ACCESS_GUILD_ID` and `NORDTAL_ACCESS_ROLES_ADMIN`;
those win over the file. The startup log lists every setting the environment overrode.

## Run it

The bot is the `bot` profile of the stack at the repository root. From the installation directory:

```bash
COMPOSE_PROFILES=db,bot docker compose --env-file /etc/nordtal/season-2.env up -d
```

To build the image locally, build the jar first; `deploy/jvm/Dockerfile` copies whatever `build/libs` holds:

```bash
./gradlew :discord-bot:shadowJar
COMPOSE_PROFILES=db,bot docker compose up -d --build
```

The container runs the jar baked into its image, which carries the release's version as its tag;
a new version arrives as a new image. The whole deployment is described in
[../deploy/README.md](../deploy/README.md).

## Where things live

- `access/`: purchases, grants, linking, the reaction to a booked payment and the managed messages.
- `hungergames/`: team registration.
- `status/`: the status channel names.
- `announce/`, `discord/`: announcements, admin commands and the update feed.
- `config/`: the three config specs and their defaults.
- `src/main/resources/messages/`: the translations.

## Texts

Every text the bot sends is a message of its bundle, rendered by `DiscordRenderer`, the Discord target of
the one message system (`:messages`); `Messages.format`, the plain target, is never called here, which
`:architecture` holds. In a markdown text each value is escaped with JDA's sanitizer, every single character
and not only pairs, so a team called `Red_Fox**` reads as written; a link is left as it is, since Discord
would keep the backslash in the address. A moment is Discord's timestamp, `<t:…:f>`, and a member a mention,
so each reader sees their own zone and nobody's time goes stale in a message that was sent days ago. A text
Discord shows without markdown (a button, a select, a modal, a channel name) is declared `PLAIN`, and its
values are placed as they are.
