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

This runs on the login path: one query, a short timeout, and a decision for what happens when the
database is unreachable. **Decide it deliberately** — failing open lets everyone in for free,
failing closed keeps everyone out. Default to failing closed with a message that says the server is
having trouble, and log loudly.

Expiry mid-session is not handled by the gate. Either accept it (access ends at the next login) or
add a periodic check that disconnects — pick one and write down which.

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
else's) and the 1:1 constraint. Then a real client against a running proxy: unlinked join shows a
code, the code works in Discord, the second join routes through, an expired grant is refused in the
right language. Packet- and login-path work is not done until a real client has done it.
