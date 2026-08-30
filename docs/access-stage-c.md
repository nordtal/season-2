# Stage C — proxy gate and account linking

Depends on stages A and B. Concept: [access-system.md](access-system.md).

## Scope

`network-control` (Velocity), plus the link-code half of the bot's link channel.

## 1. Login gate

On login, one call to `AccessDirectory.accessState(uuid)`, then:

1. **Not linked** → upsert a link code for this UUID (10 minutes, one per UUID, a repeat attempt
   returns the same code) and disconnect showing it. English first, German below in grey italics:
   the language is unknown at this point because the account is unknown.
2. **Not a member, or banned** → disconnect pointing at Discord.
3. **No active access** → disconnect pointing at the contribution channel, in the player's language
   from `discord_user.locale`.
4. Otherwise route on.

This runs on the login path: one query and a short timeout.

**When the database is unreachable** (decided 2026-08-30): fall back to a short-lived in-memory
cache of last-known states, and let through only players who are in it with access that was active
when it was cached. Everyone else is refused with a "we are having trouble" message, and the failure
is logged loudly. Rules that keep this from becoming a second source of truth:

- The cache is written only on a successful query — it never invents an entry.
- Entries are usable in fallback for a bounded window (start at 15 minutes) and are then gone. A
  long outage therefore closes the door rather than leaving it open forever.
- It is consulted **only** while the database is unreachable, never as a read-through cache on the
  healthy path. Access must not be decided from stale data while the truth is available.
- It lives in the proxy process and dies with it. No file, no second database.

**Expiry mid-session** (decided 2026-08-30): a periodic check warns the player in chat a few minutes
before their access ends, then disconnects them when it does. Warning lead time and check interval
come from configuration. The warning is in the player's language.

## 2. Link redemption

Bot side: the link channel message's button opens a modal for the code. On submit — validate, check
expiry, enforce 1:1 (the database constraints do the enforcing), write `account_link`, delete the
code, confirm, and write the audit entry.

Unlink: available to the user with no waiting period, and **always** reported to the admin channel
with the Discord user and the Minecraft account. That log is the only thing making a shared access
visible.

## 3. Dependencies

The proxy shades JDBI + HikariCP + driver + `common`, not `jcore`. `app.simplecloud.api:api` stays
`compileOnly` and is never shaded.

## Verification

Testcontainers for code lifecycle (issue, refresh, expire, redeem, redeem twice, redeem someone
else's) and the 1:1 constraint. The fallback cache needs its own tests: an unreachable database lets
a recently-seen player in, refuses an unknown one, and refuses everyone once the window has passed. Then a real client against a running proxy: unlinked join shows a
code, the code works in Discord, the second join routes through, an expired grant is refused in the
right language. Packet- and login-path work is not done until a real client has done it.
