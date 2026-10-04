# discord-bot

The season 2 Discord bot: access purchases and grants, account linking, roles, the Hunger Games
registration, status channels and the admin log. It talks to PostgreSQL and the Discord gateway and
to nothing else, so it runs without any Minecraft server.

It holds no bunq key and books nothing. The bot writes a `payment_request` row for the wish to buy;
`steward` has `steward-bunq` create the bunq.me tab, writes the link back, finds the money and books it,
and the bot is told through its inbox (`PAYMENT_BOOKED`) and reacts: the role, the direct message, the
thank-you in the channel and the admin note. The prices are the network's `prices` group.

An admin's preview of a text shown in Discord (`PREVIEW_MESSAGE`) is a direct message to that admin, rendered as
the key renders. The inbox thread waits for Discord's answer, so a closed direct message is the request's refusal
(`NOT_DELIVERED`) rather than a log line, and Steward words the delivered case itself.

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
those win over what is stored. The startup log lists every setting the environment overrode.

## Run it

The bot is the `bot` profile of the stack at the repository root. From the installation directory:

```bash
COMPOSE_PROFILES=db,bot docker compose --env-file /etc/nordtal/season-2.env up -d
```

To build the image locally, stage the jar first; `deploy/jvm/Dockerfile` copies `build/image/app.jar`:

```bash
./gradlew :discord-bot:imageContext
COMPOSE_PROFILES=db,bot docker compose up -d --build
```

The container runs the jar baked into its image, which carries the release's version as its tag;
a new version arrives as a new image. The whole deployment is described in
[../deploy/README.md](../deploy/README.md).

## Where things live

- `access/`: purchases, grants, linking, the reaction to a booked payment and the managed messages.
- `registration/`: team registration for a game, named by its key; the Hunger Games is the one game so far.
- `status/`: the status channel names.
- `announce/`, `discord/`: announcements, admin commands and the update feed.
- `config/`: the three config specs and their defaults.
- `src/main/resources/messages/`: the translations.

## Registration

The bot owns registration: `registration`, `team` and `team_member`, each round named by its game's key from
`Game` in `:database`. The flow and `Teams` take the game, so a second team mode is a constant there and its
own Register message, not a copy of the flow. The game owns everything after: the Hunger Games server creates a
game from the round when it starts, and it alone moves the round's state. A closed round refuses every change
until its game is aborted, which opens it again with its teams, or decided, which ends it; the next registration
then opens a new round.

## Texts

Every text the bot sends is a message of its bundle, rendered by `DiscordRenderer`, the Discord target of
the one message system (`:messages`); `Messages.format`, the plain target, is never called here, which
`:architecture` holds. In a markdown text each value is escaped with JDA's sanitizer, every single character
and not only pairs, so a team called `Red_Fox**` reads as written; a link is left as it is, since Discord
would keep the backslash in the address. A moment is Discord's timestamp, `<t:…:f>`, and a member a mention,
so each reader sees their own zone and nobody's time goes stale in a message that was sent days ago. A text
Discord shows without markdown (a button, a select, a modal, a channel name) is declared `PLAIN`, and its
values are placed as they are.

The admin channel reads the admin bundle, `messages/admin` of `:database`, which Steward's page renders too. A
journal line the bot writes (a grant, a revocation, a link, an unlink, a play time) goes through `AdminLog.record`:
the row stores the message, and the card in the admin channel is that same message rendered here, its action as the
title, who did it and whom it concerns as fields. There is no second sentence for the channel, so the journal and
the card cannot say different things. An alert is the same: the bot raises it as messages of that bundle, and the post
steward routes back is drawn from them, each value escaped, with the link to its page below. A note (a booked payment,
too many wrong link codes, a grant before the season has a start) and the update feed are drawn from the same bundle,
in English, and who asked for a run is named as the journal names who did something. Only what a member reads is in
the bot's own bundle, in every language. The one exception is an announcement: a server and Steward write it as
messages of `:database`'s bundle, which the bot loads too and renders in each channel's language.
