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
  keys and browsers) and the line as a message of the admin bundle. The Journal page draws the actor as a profile and
  renders the line and its action through the web target; the actions feed carries a run's label and extent the same
  way.
- **The live stream.** A signed-in browser holds one `GET /api/live` (`KEY_HELD`), an SSE stream of
  `change` events whose data is `{topic, version}`: a topic of `live.Topic` and a short hash of its new
  answer, never the answer itself. The browser refetches what the topic covers from the route that owns
  it, so every answer is shaped in one place. `LiveFeed` re-reads a topic in full from the same reads
  those routes answer with and announces it when the hash moved. It reads only while somebody listens,
  and the first read after nobody is recorded silently. The hub rings it on the channels in
  `Web.LIVE_CHANNELS` and on its minute; the topics only the agent knows (containers, host, topology)
  are read every ten seconds as well. `nordtal_smp` is not among the channels: play moves the track
  many times a minute, and every signal also runs a payment pass. A request in a server's or the bot's
  inbox moves its topic by `Inbox.version()`, so a page waiting for an answer waits on the stream.
  Logs are not on it: a log is its own follow, relayed from the agent once.
- **The API's types.** Every route answers and reads records, declared in the feature package of the
  route, and the frontend's types in `frontend/src/lib/api.gen.ts` are written from them by
  `./gradlew :steward:generateApiTypes`; `ApiTypesTest` fails on `check` while the committed file
  differs. The generator is `ApiTypes` in the test sources, a small reflection over
  record components, chosen over the maintained generators because none of them reads JSpecify's
  type-use `@Nullable`, which is what makes a field optional. Each package lists its top-level records
  in a test-source `*Wire` class and `ApiRoots` gathers them; what they hold is reached from them. A
  `@Nullable` component is an absent field, never a `null`, since the codec drops nulls. `WireJson` is
  the one codec of the routes and names the enums spelled in lowercase, for both sides. No fetch
  functions are generated: the routes are registered by hand, so there is no catalogue to write them
  from. Three types stay written by hand in `lib/api.ts`: the bodies of a settings save and a
  message save, where a `null` resets a value and an absent key leaves it, which an optional field
  cannot say, and the glyph manifest, which is a file of the pack build rather than an answer.
- **Its own texts.** Every word the page shows is a key of the `steward` bundle, English only, declared by
  `texts.StewardTexts` and overridable like any other. `GET /api/texts` serves every key's variants as parsed
  trees, overrides layered, and is open before sign-in, since the sign-in page reads its words from it too; the
  page draws once they are read. `lib/texts.ts` is the web target: it fills a tree into text nodes, never markup,
  and formats each value in `en-GB` and the browser's zone. A span outside a sentence, an uptime or a play time
  cell, goes through the same duration kind (`span`, `since`), so a cell and a sentence read alike.
  `t(key, values)` is typed by `texts.gen.ts`, and `texts.gen.json` is the packaged English the page falls back
  to; `generateApiTypes` writes both and `TextTypesTest` fails while either differs. A label chosen by an enum is a `select` on it, the constant in kebab
  case (`choice`). A refusal a person meets is a `texts.RequestRefused`: the status, a message of the bundle and the
  code the page branches on, rendered with the overrides by the error handlers, so the page shows the error as it
  comes. A refused write of `:database` (`Refused`) reaches the same handlers and is worded by the database bundle
  with its overrides, the bundle the announcements list renders in; a stale key on the track is refused as the SMP
  refuses it, with its reason and its words. What answers a malformed request is read by whoever wrote the client and stays a literal, like a log line.
  A page's own words are a section of their own, one interface per page beside `texts.StewardTexts`, and the words
  every settings dialog shares are `texts.Forms`.
  What Steward says back as data, a save's effect, an announcement's line or why the guild cannot be listed, is a
  message the page renders; a server's own answer passes through `steward.said.words` until that server words it.
- **Game data and pickers.** `GET /api/game-data` serves the union of the servers' catalogues for
  the newest version with the icon sheet's index; the sheet itself is cached for good per version.
  The settings form draws one picker per field a schema marks with `refers`, from the catalogue, the
  guild or the people Steward knows: search over name and id, tags and namespaces as filters,
  advancements as their tree, a list as chips, an id nothing lists as a warning chip that stays, a
  bottom sheet on a phone. Where nothing can be listed it falls back to the typed field and says why.
  The milestone track joins the database's progress to the group's own sections by their `key` and
  draws each section's settings through the same schema, so nothing in it knows what a milestone holds.
- **Custom editors.** A plugin's descriptor may name an editor per group; the frontend's registry
  draws that group with it only when the editor reads the document it got, and with the form built
  from the schema otherwise. An editor knows the structure it lays out, never a label, a choice or a
  reference, and saves through the same draft as the form. Proposals for one live under `/designs`.
- **Translations.** A bundle is a tree of its keys, and one key is open at a time, inline at every width, so the
  page's own save serves it. The key's text is edited as it looks (A), runs of styled text with values as pills, or
  as it is written (B), the source coloured from the marks of the one parser in `lib/message-tree.ts`; A builds on
  that parser too (`lib/rich-text.ts`) and is offered only while the text reads. The tools above the field offer
  exactly what the key's format allows: its values with their examples (the server's live ones per context type
  from `GET /api/message-examples`, else the schema's), glyphs, tones and colours, a value's style, hover, click
  and the key's actions. Whether a text is right is only ever `MessageCheck`: the editor asks
  `GET /api/message-check` once typing pauses and shows what it says, and the save refuses what it errs on. The bundle
  names the network's languages (`languages`), which the editor offers before any jar ships one, and each tone's
  colour as the settings of the bundle's service give it (`colours`), which the preview draws with; both are read
  per request, so a saved setting shows at once. `GET /api/message-syntax` names each kind's styles. An override no process shows (`GET /api/message-fallbacks`) opens
  with what it was written over and what the jar has now; taking it over saves it as it stands. The send button
  asks for a preview of the text as it stands (`POST /api/message-preview`, answered like a game action): a key
  shown in Discord comes as a direct message from the bot, any other to the admin's linked player on the server
  the roster has them on, each filled with the examples the editor shows and painted in the service's colours. The bundle names which keys a preview
  reaches (`previews`); one shown in Steward or as a push has none.
- **Alerts.** Every alert is a row in `admin_alert`, raised by whoever saw it: steward measures the
  stack every 30 seconds against the `web` group's thresholds and raises a failed run and a payment
  nobody can book, once per bank payment; the bot raises what it could not do in Discord or with a purchase. Steward routes each row once, to Web Push and to the admin channel through the
  bot's inbox, per alert type and per admin. An alert is messages of the admin bundle: a push carries its title and
  level rendered here, in plain text, since a lock screen renders nothing; the bot renders the post for Discord, and
  the browser renders `GET /api/alerts` itself.
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
and one to `alerts` applies at the next reading.

Where a run's versions come from is `steward-agent`'s `runs` group.

## Tests

`./gradlew :steward:test` needs no network; its stack tests talk to `AgentStandIn`, the agent's API
over a fake daemon, through the real client.
