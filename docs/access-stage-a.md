# Stage A — schema, access API, message system

Prerequisite for B and C. Concept: [access-system.md](access-system.md).

## Scope

`common` and the database schema. No Discord code, no bunq code, no proxy code.

## 1. Schema (Flyway, in the bot module)

`V1__contribution.sql` is **rewritten in place**, not superseded — nothing has ever run it in
production and season 2 starts empty. Any local dev database must be dropped. The `contribution`
table and `ContributionTier` disappear entirely.

Tables (PostgreSQL, `gen_random_uuid()` for surrogate keys, money as `numeric`, never `float`):

- **`discord_user`** — `discord_id varchar(32) PK`, `locale varchar(8) NOT NULL DEFAULT 'en'`,
  `member_state` (`MEMBER` / `LEFT` / `BANNED`), `donor boolean NOT NULL DEFAULT false`,
  `updated timestamp`.
- **`account_link`** — `discord_id` UNIQUE, `mc_uuid uuid` UNIQUE (1:1 enforced by the database, not
  by application code), `linked timestamp`.
- **`link_code`** — `code varchar(16) PK`, `mc_uuid uuid` UNIQUE (one live code per UUID — the
  refresh path is an upsert on this constraint), `created`, `expires`.
- **`payment_request`** — `id uuid PK`, `reference varchar(16)` UNIQUE, `discord_id`, `days int`,
  `amount_cents int`, `donation_cents int`, `status` (`OPEN` / `PAID` / `EXPIRED` / `CANCELLED` /
  `SUPERSEDED`), `bunq_tab_id bigint`, `share_url text`, `bunq_payment_id bigint` (nullable, UNIQUE
  where not null — the one thing that actually prevents double booking), `created`, `expires`,
  `settled`.
- **`access_grant`** — `id uuid PK`, `discord_id`, `valid_from`, `valid_until`, `source`
  (`PURCHASE` / `ADMIN`), `payment_request_id` (nullable), `revoked` (nullable), `created`.
- **`audit_log`** — append-only: link, unlink, admin grant/revoke, manual settle. Who, what, when.

Indexes: `access_grant (discord_id, valid_until)`, `payment_request (status)`,
`link_code (expires)`.

## 2. Access API in `common`

Platform-free (no Paper, no Velocity types), consumed by the bot, the proxy and any plugin.

```java
public interface AccessDirectory {
    Optional<UUID>   linkedMinecraftAccount(String discordId);
    Optional<String> linkedDiscordAccount(UUID mcUuid);
    AccessState      accessState(UUID mcUuid);   // linked? member? banned? valid until?
    Locale           locale(UUID mcUuid);        // EN fallback, never throws
    boolean          isDonor(String discordId);
}
```

- **Appending is the API's job, not the caller's.** `grantAccess(discordId, days, source)` computes
  `valid_from = max(now, current valid_until)` in one statement. Two callers must not each write
  their own version of that rule.
- `accessState` answers the proxy's three questions in **one** query — a login path must not make
  three round trips.
- JDBI SqlObject interfaces, same style as the existing `ContributionDao`. No generic CRUD layer.

## 3. Message system in `common`

Bundle format, locale resolution, EN fallback. Every user-visible string in season 2 goes through
it: bot embeds, DMs, disconnect screens, plugin messages. Keep it small — a map per locale loaded
from resources, a lookup with parameters, a fallback that logs a missing key once rather than per
call.

## 4. Dependencies

`common` currently compiles against nothing. It now needs JDBI + HikariCP + the PostgreSQL driver —
declared so consumers shade **only these**, never the whole `jcore` block. Verify the resulting
Paper plugin jar size before calling this done; the bot's ~33 MB is the number not to approach.

## Verification

Testcontainers against real PostgreSQL, driven from `@BeforeAll` by hand (this repo is on the JUnit
6 BOM; the `junit-jupiter` Testcontainers extension is built for 5):

- appending: grant 30 days, then 30 more with 12 left → `valid_until` is 42 days out, not 30
- expiry boundary: a grant ending one second ago is not active
- revoke: a revoked grant never counts, even inside its window
- `accessState` for: unknown UUID, linked but no access, linked and banned, linked and active
- locale: unknown user → EN, never an exception
- the `payment_request.bunq_payment_id` unique constraint actually rejects a second booking
