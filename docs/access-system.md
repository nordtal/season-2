# Access system — concept

Status: agreed 2026-08-30. This document is the shared understanding the three stage plans in
`docs/access-stage-*.md` implement. It replaces season 1's contribution model entirely; nothing is
migrated (see the workspace `CLAUDE.md`).

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

Prices, role ids and channel ids live in configuration, never in an enum. A price change must not
need a release.

### The rules

- Access is bought by and bound to a **Discord account**. The Minecraft account linked to it may
  join; nothing else may.
- Buying while access is still running **appends**: the new period starts when the current one
  ends. Nobody loses paid time by renewing early.
- The **donor role is permanent**. The bot grants it and never takes it away — which is also what
  makes it safe to hand out by hand through Discord's role UI without fighting the bot.
- The **access role is strictly bot-owned**. Granting it manually holds only until the next
  reconcile; `/grant-access` is the supported path.
- A Discord ban does not pause anything: the paid period keeps running down. Unbanned before it
  expires, the rest is still usable; otherwise it is gone.

## Source of truth

**The database is the truth.** Discord roles are a projection of it for Discord's own UI, and
LuckPerms is not involved at all.

The alternative — Discord role as truth, carried into LuckPerms by DiscordSRV — was rejected: it
puts five stations (payment → DB → Discord role → sync cycle → LuckPerms group → plugin) between a
payment and a join, three of them asynchronous, to express something the database already expresses
exactly. Access ends at a timestamp; a role is on or off and moves on a sync cycle. DiscordSRV is
not used at all — the account link is built in-house (stage C), which also means no chat bridge; if
one is wanted later, that is a separate decision.

The schema stays owned by the bot module (`db/migration` there, applied by the bot at startup) even
though the API that reads it lives in `:common` — decided 2026-08-30. Exactly one process may change
the schema, and Flyway must not come anywhere near `:common`, or it lands in every plugin jar. The
cost is a documented path from `:common`'s tests to the bot's migration directory.

Plugins and the proxy read the database directly through a `common` API and shade only what they
need (JDBI + HikariCP + driver), never the full `jcore` dependency block — a Paper plugin must not
carry the bot's ~33 MB jar.

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

Both are gated by a configured **watermark timestamp**: payments older than it are ignored. Without
it the first run against an empty database books up to 50 historical payments, roles and messages
included.

Amounts are not trusted to match: a bunq.me amount is a suggestion the payer can edit. The rule is
**pay what you get** — the highest tier whose price is covered by the amount actually received; a
remaining 5 € on top counts as the donation. Below the lowest price nothing is granted and the case
is raised to admins.

### Joining Minecraft

The proxy (`network-control`) decides, in this order:

1. **Linked?** If not: generate or refresh a link code for this UUID and disconnect showing it.
   English first, German underneath in grey italics — at this point the player's language is not
   known. The login attempt itself is what proves the UUID, so no waiting room is needed.
2. **Discord member and not banned?** If not: disconnect with a pointer to Discord.
3. **Access active?** If not: disconnect pointing at the contribution channel, in the player's
   language.
4. Otherwise: route to the game server.

If the database is unreachable, the proxy falls back to a short-lived cache of last-known states and
admits only players it saw recently with active access; everyone else is refused. Access that runs
out mid-session warns the player in chat and then disconnects them. Both are specified in stage C.

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

Four bot-maintained messages in four role-gated channels: contribution DE/EN and link DE/EN. Banner
images live in the module's resources and are attached via `attachment://`, never a Discord CDN URL
— season 1's embed URLs carried expiry parameters and are dead.

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

## Deployment

The module is renamed **`payments-bot` → `access-bot`** (`eu.nordtal.s2.accessbot`,
`ghcr.io/nordtal/access-bot`), which is cheap only while nothing runs in production. The Discord
application is to be named "Nordtal Access".

## Verification

1. Testcontainers against real PostgreSQL for the time logic: appending, expiry, downgrade on a
   short payment, one-open-request-per-person.
2. bunq **sandbox** for the payment path end to end. `ApiEnvironmentType.PRODUCTION` is hardcoded
   today; an environment switch is part of stage B.
3. The real guild, in a channel only admins can see, for buttons, modals, ephemeral messages and
   role assignment.
4. A real 3 € purchase as the final step, never as the development loop.

"It compiles" is not verification, and neither is a green build on a machine without Docker — the
integration tests skip themselves there.

## Known risks

- **SimpleCloud on Minecraft 26.2 is unconfirmed** (carried over from the season-2 notes).
- **The proxy needs database access**, so PostgreSQL must be reachable from the proxy host and the
  credentials exist in more than one config file.
- **No chat bridge.** Dropping DiscordSRV drops that too; nobody has asked for it, but it is a
  consequence worth stating.
- **`BunqRequestBuilder` must stay.** Resolved 2026-08-30: JDA pulls OkHttp 5, where
  `Request.Builder.delete()` is final and `okhttp3.internal.Util` no longer exists, and the SDK's
  original class overrides exactly that method. It is required as long as JDA and the bunq SDK share
  a classpath.
