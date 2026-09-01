# Access system — concept

Status: agreed 2026-08-30, built the same day. This is the shared understanding behind the code;
the three stage plans it was written for are gone, because the code is now the fact. It replaces
season 1's contribution model entirely; nothing is migrated (see the workspace `CLAUDE.md`).

## What is being built

Season 2 gates the Minecraft network on a **paid access period bought in Discord**. Season 1's
one-off "contribution tiers" are gone: there is no Settler/Citizen/Knight/Lord ladder, no bank
transfer, no PayPal. One payment method (bunq.me card payment), one thing being sold (days of
access), one optional extra (a donation that earns a permanent role).

### The product

| item | value |
|---|---|
| 30 days of access | 3 € |
| 60 days of access | 5 € |
| 90 days of access | 7 € |
| donation surcharge | +5 €, optional, grants a permanent donor role |

Prices, role ids and channel ids live in configuration, never in an enum, and the tiers are a
**list** rather than a fixed set — a fourth tier is a config edit. A price change must not need a
release.

### The rules

- Access is bought by and bound to a **Discord account**. The Minecraft account linked to it may
  join; nothing else may. Buying for somebody else is not offered — season 1's receiver select is
  gone with the rest of the contribution model.
- Buying while access is still running **appends**: the new period starts when the current one
  ends. Nobody loses paid time by renewing early.
- The **donor role is permanent**. The bot grants it and never takes it away — which is also what
  makes it safe to hand out by hand through Discord's role UI without fighting the bot.
- The **access role is strictly bot-owned**. Granting it manually holds only until the next
  reconcile; `/grant-access` is the supported path.
- A Discord ban does not pause anything: the paid period keeps running down. Unbanned before it
  expires, the rest is still usable; otherwise it is gone.
- Expiry is announced: the bot DMs the user **three days before** access ends and again when it
  does. A DM that cannot be delivered is reported to the admin channel, never logged and forgotten.

## Source of truth

**The database is the truth.** Discord roles are a projection of it for Discord's own UI, and
LuckPerms is not involved at all.

The alternative — Discord role as truth, carried into LuckPerms by DiscordSRV — was rejected: it
puts five stations (payment → DB → Discord role → sync cycle → LuckPerms group → plugin) between a
payment and a join, three of them asynchronous, to express something the database already expresses
exactly. Access ends at a timestamp; a role is on or off and moves on a sync cycle. DiscordSRV is
not used at all — the account link is built in-house (stage C), which also means no chat bridge; if
one is wanted later, that is a separate decision.

Exactly one process applies the schema — the bot, at startup — and Flyway must not come anywhere
near `:common`, or it lands in every plugin jar. Decided 2026-08-30 and unchanged. The `.sql` files
themselves moved into `:common` on 2026-08-31 so that the DDL sits beside the API that reads it;
both the bot and `:common`'s tests find them at `classpath:db/migration`. See
[architecture.md](architecture.md#schema-ownership).

Plugins and the proxy read the database directly through a `common` API and shade only what they
need (JDBI + HikariCP + driver), never the full `jcore` dependency block — a Paper plugin must not
carry the bot's ~33 MB jar.

Two rules belong to that API rather than to any of its callers. **Appending is the API's job**:
granting computes `valid_from = max(now, current valid_until)` in one statement, so two callers
cannot each write their own version of that rule. And **`accessState` answers all of the proxy's
questions in one query** — linked, member, banned, valid until — because a login path must not make
three round trips.

## Flow

### Buying access

1. The bot maintains a message with a button in the contribution channel (one per language).
2. Button → ephemeral: choose 30 / 60 / 90 days.
3. Summary with the computed amount, and buttons to confirm, change the selection, or toggle the
   +5 € donation.
4. Confirm → a `payment_request` row is written, a reference `NT-XXXXXX` (6 random hex, UNIQUE) is
   allocated, a bunq.me tab is created carrying that reference as its description, and the share
   URL is shown ephemerally.
5. Payment arrives → access period is appended, roles are set, the user gets a DM. If a donation
   was included, the contribution channel gets a public thank-you; a plain access purchase stays
   private.

**One open request per person.** Starting a new one cancels the previous open one, and cancelling
means the bunq tab is actually closed via `BunqMeTabApiObject.update(tabId, accountId, "CANCELLED")`
— not just a status flip in our own table. Requests expire after **24 h** and are cancelled the
same way.

### Matching a payment

Two independent paths, both stored on the request:

1. **Primary — the tab itself.** `BunqMeTabApiObject.get(tabId, accountId).getValue().getResultInquiries()`
   returns the actual `PaymentApiObject`s that settled that tab. This is an exact link, verified in
   the SDK sources on 2026-08-30 (`BunqMeTabResultInquiryApiObject.getPayment()`), and needs no text
   parsing at all.
2. **Fallback — the reference.** Payments are scanned for `NT-[0-9A-F]{6}` and matched against open
   requests. This covers anything that reaches the account outside a tab.

Both are gated by a **watermark timestamp**: payments older than it are ignored, completely and
forever. Without it the first run against an empty database books up to 50 historical payments,
roles and messages included.

The watermark **sets itself**: the first start that finds none stores the instant of that start in
`bot_setting`, and it is never rewritten. A configured value in `access.yml` overrides it for
somebody who deliberately wants a different cut-off, without replacing the stored one — so removing
the override falls back to the original first start rather than to whenever the bot last restarted.
A date written into the config in advance was the earlier design and is unguessable: too early
books history, too late silently ignores real purchases.

Amounts are not trusted to match: a bunq.me amount is a suggestion the payer can edit. The rule is
**the order wins when the money covers it**, and it is deliberately asymmetric:

- The `payment_request` row records what was ordered — how many days, and whether the donation was
  included. When the amount that arrives covers that total, **exactly what was ordered is granted**.
  Ordering 60 days with a donation and paying the 10 € it asks for gives 60 days and the donor
  role.
- Only a payment that falls **short** of the order is re-derived from the amount: the granted tier
  becomes the highest one the amount does cover, and a remainder of at least the surcharge on top
  of *that* price is a donation. Below the lowest price nothing is granted and the case is raised
  to admins.
- **Surplus** above the ordered total is a donation once it reaches the surcharge and one was not
  already ordered. Otherwise it is ignored — no extra days, no partial credit. Days are bought in
  tiers.
- A payment with no order behind it — an unknown reference — falls back to the amount alone. In
  practice that case is raised to admins rather than booked, so nothing settles without an order.

An earlier version of this document derived both directions from the amount ("the highest tier
whose price is covered"). That was wrong: it meant paying the asked-for 10 € on a
60-days-with-donation order bought 90 days and *no* donor role. Corrected 2026-08-30.

### Joining Minecraft

The proxy (`network-control`) decides, in this order:

1. **Linked?** If not: generate or refresh a link code for this UUID and disconnect showing it.
   English first, German underneath in grey italics — at this point the player's language is not
   known. The login attempt itself is what proves the UUID, so no waiting room is needed.
2. **Discord member and not banned?** If not: disconnect with a pointer to Discord.
3. **Access active?** If not: disconnect pointing at the contribution channel, in the player's
   language.
4. Otherwise: route to the game server.

If the database is unreachable, the proxy falls back to a short-lived in-memory cache of last-known
states and admits only players it saw recently with active access; everyone else is refused with a
"we are having trouble" message, and the failure is logged loudly. Four rules keep that cache from
turning into a second source of truth:

- It is written only on a successful query — it never invents an entry.
- Entries are usable for a bounded window (15 minutes to start with) and are then gone, so a long
  outage closes the door rather than leaving it open forever.
- It is consulted **only** while the database is unreachable, never as a read-through cache on the
  healthy path. Access must not be decided from stale data while the truth is available.
- It lives in the proxy process and dies with it. No file, no second database.

Access that runs out mid-session is caught by a periodic check: it warns the player in chat, in
their language, a few minutes before the end and disconnects them when it arrives. The lead time
and the check interval are configuration, not constants.

Membership state is not queryable from the proxy, so the bot maintains `discord_user.member_state`
from guild events (join / leave / ban / unban) plus a reconcile at startup for anything it missed
while down.

### Linking

- A join attempt produces a code, valid **10 minutes**, **one per UUID** — a repeated attempt shows
  the same code rather than minting another, which makes join-spam pointless without a rate limiter.
- The link channel (one per language) carries a bot-maintained message with a button opening a modal
  to enter the code.
- **1:1**, and the user may unlink themselves with no waiting period. Every link and unlink is
  written to the admin channel with the Discord user and the Minecraft account, because without a
  waiting period that log is the only thing that makes passing an access around visible.

## Language

DE/EN, English is the default and the fallback. The choice is made through **Discord's own
onboarding as a role** — configured by hand, the bot owns none of it. The bot reads the role and
mirrors it into `discord_user.locale` on `GuildMemberRoleUpdate`, plus a reconcile at startup, so
the value is current for players who are offline or change it months later.

Four bot-maintained messages in four role-gated channels: contribution DE/EN and link DE/EN. Their
message ids are stored, so a restart edits the existing message instead of posting a second one, and
the prices they show are rendered from configuration every time — a stale price in an embed nobody
re-posted is exactly what that prevents. Banner images live in the module's resources and are
attached via `attachment://`, never a Discord CDN URL — season 1's embed URLs carried expiry
parameters and are dead.

Season 2 is multilingual beyond the bot: `common` gets the message system (bundle format, locale
resolution, EN fallback) that the plugins use for their own text.

## Admin surface

One admin channel; entries that need a human mention an admin role, routine audit entries do not.

- `/grant-access <user> <days>` — replaces `/manual-con`
- `/revoke-access <user>`
- `/access-status <user>` — valid-until, history, open requests
- `/settle <ref>` — book a request by hand, with autocompletion over open references
- Reported there: unmatchable payments, payments on expired references, failed DMs, role errors,
  and every link/unlink

Every one of these commands is `DefaultMemberPermissions.DISABLED` and writes to the append-only
audit log; being admin-only in Discord's UI is not the same as being recorded.

## Deployment

The module is **`discord-bot`** (`eu.nordtal.s2.discordbot`, `ghcr.io/nordtal/discord-bot`). It was
renamed from season 1's `payments-bot` to `access-bot` on 2026-08-30 and again to `discord-bot` on
2026-08-31, the second time because the module must not be named after one of its features — it now
carries `access/` and `hungergames/` as sibling packages. The Discord application is named "Nordtal
Access". Both renames were free because nothing has ever run in production.

## Verification

1. Testcontainers against real PostgreSQL for the time logic: appending, expiry, downgrade on a
   short payment, one-open-request-per-person. Containers are started and stopped from `@BeforeAll`
   by hand — this repo is on the JUnit 6 BOM and the `junit-jupiter` Testcontainers extension is
   built for 5.
2. bunq **sandbox** for the payment path end to end. The environment is a config key
   (`BotSpec#bunq().environment()`) rather than a hardcoded `PRODUCTION`, so the sandbox run needs no
   code change. **Still unrun** — nothing in the test suite touches bunq.
3. The real guild, in a channel only admins can see, for buttons, modals, ephemeral messages and
   role assignment.
4. A real 3 € purchase as the final step, never as the development loop.

"It compiles" is not verification, and neither is a green build on a machine without Docker — the
integration tests skip themselves there.

## Known risks

- ~~SimpleCloud on Minecraft 26.2 is unconfirmed.~~ **Moot since 2026-09-01: season 2 does not run
  on SimpleCloud.** It was confirmed to run 26.2 on 2026-08-31 and the platform was then dropped
  anyway — production is a single `docker compose` stack, see
  [../deploy/README.md](../deploy/README.md#why-it-looks-like-this). The API artefact was a separate
  question and was answered earlier by deleting the dependency.
- **The proxy needs database access**, so PostgreSQL must be reachable from the proxy host and the
  credentials exist in more than one config file.
- **No chat bridge.** Dropping DiscordSRV drops that too; nobody has asked for it, but it is a
  consequence worth stating.
- **`BunqRequestBuilder` must stay.** Resolved 2026-08-30: JDA pulls OkHttp 5, where
  `Request.Builder.delete()` is final and `okhttp3.internal.Util` no longer exists, and the SDK's
  original class overrides exactly that method. It is required as long as JDA and the bunq SDK share
  a classpath.
