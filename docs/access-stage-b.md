# Stage B — the bot

Depends on stage A. Concept: [access-system.md](access-system.md).

## Scope

Rebuild `payments-bot` as `access-bot`. Everything Discord-facing and everything bunq-facing.

## 1. Rename

`payments-bot` → `access-bot`; package `eu.nordtal.s2.paymentsbot` → `eu.nordtal.s2.accessbot`;
image `ghcr.io/nordtal/payments-bot` → `.../access-bot`. Touches `settings.gradle.kts`, the
Dockerfile, `.github/workflows/release.yml`, both `CLAUDE.md` files. Do it first, in its own commit,
so the rest of the diff is readable.

## 2. What is deleted

`ContributionTier`, `PaymentMethod`, `SetupFlow`, `Contribution`, `ContributionDao`,
`ContributionRepository`, `ContributionService`, the `updateRoles()` full-member scan, the balance
voice channel (`PaymentProcessingSpec.BalanceSpec` and its 5-minute bunq call), `/test-con`,
`/manual-con`, `/send-contribution-embed`, and the receiver select menu — buying for someone else
is gone.

`BunqRequestBuilder` **stays**, with the explanation now in its javadoc: JDA pulls OkHttp 5, where
`Request.Builder.delete()` is final and `okhttp3.internal.Util` is gone.

## 3. Managed messages

Four channels (contribution DE/EN, link DE/EN) from configuration. On startup the bot posts or
edits its message, storing message ids so a restart never leaves a duplicate. Prices in the embed
are rendered from configuration — a stale price in an embed nobody re-posted is exactly what this
prevents. Banner images ship in `src/main/resources` and are attached with `attachment://`; add a
placeholder now, the real artwork is swapped in later.

## 4. Purchase flow

Button → day selection (30/60/90) → summary with the amount and three buttons (confirm, change,
toggle +5 € donation) → on confirm:

1. Cancel any open request of this user, **including its bunq tab**
   (`BunqMeTabApiObject.update(tabId, accountId, "CANCELLED")`), status `SUPERSEDED`.
2. Allocate `NT-XXXXXX` — 6 random hex, insert, retry on unique violation.
3. Create the bunq.me tab with amount and the reference as description; store tab id and share URL.
4. Show the URL ephemerally, with the reference and the 24 h validity.

Flow state belongs in the request row, not only in a Guava cache — season 1's cache meant a restart
silently dropped every half-finished flow.

## 5. Payment processing

Poll on an interval. For each open request, read its tab's `getResultInquiries()` and take the
`PaymentApiObject` from there — the exact link. Additionally scan payments for
`NT-[0-9A-F]{6}` as a fallback. Ignore everything before the configured watermark timestamp.

On a match: derive the granted tier from the **amount actually received** (highest tier covered; a
remaining 5 € is the donation), call the stage-A grant API, set roles, DM the user, post publicly
only if a donation was included. Below the lowest price: grant nothing, raise it to admins with a
mention.

Expiry sweep: requests past 24 h → cancel the tab, status `EXPIRED`. A payment landing on an expired
reference is never booked automatically — it goes to admins.

## 6. Roles

- Access role: bot-owned. Reconcile on a timer over members who have the role or hold a grant —
  **not** `loadMembers()` over every guild every 10 seconds.
- Donor role: granted, never removed. The bot must not touch it during reconcile, which is what
  makes manual assignment through Discord's UI safe.
- DMs: 3 days before expiry, and on expiry. A blocked DM is caught and reported to the admin
  channel, not logged and forgotten.

## 7. Guild state and locale

Listen for member join / leave / ban / unban and role updates; write `member_state` and `locale`
into `discord_user` immediately, and reconcile once at startup for whatever happened while the bot
was down. The proxy depends on this data being current — it cannot ask Discord itself.

## 8. Admin commands

`/grant-access`, `/revoke-access`, `/access-status`, `/settle <ref>` (autocomplete over open
references). All `DefaultMemberPermissions.DISABLED`, all writing to the audit log.

## 9. Configuration

`access.yml` (`NORDTAL_ACCESS_*`): prices per tier, donation amount, role ids (access, donor,
DE, EN, admin-ping), channel ids (4 managed + admin), poll interval, request TTL, link-code TTL,
watermark timestamp, reminder lead time. `bot.yml` gains a **bunq environment switch** —
`ApiEnvironmentType.PRODUCTION` is hardcoded today and the sandbox test needs it. `database.yml`
unchanged. Every value stays overridable by environment variable; credentials keep empty defaults
and the bot refuses to start while they are empty.

## Verification

Testcontainers for the request state machine (supersede, expire, downgrade, double-book rejection);
bunq sandbox for tab creation, cancellation and result inquiries; the real guild in an admin-only
channel for the full click-through; a 3 € real purchase last.
