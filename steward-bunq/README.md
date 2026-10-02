# steward-bunq

The one process that holds the bank key. It creates and cancels bunq.me tabs and lists incoming
payments, and it decides nothing: which request a payment pays, whether it is late, and what access
it buys are `steward`'s and the bot's. Without `NORDTAL_STEWARD_BUNQ_API_KEY` and
`NORDTAL_STEWARD_BUNQ_ACCOUNT_ID` it still starts, says `configured: false` on `/api/account` and
`503` on every other bank route, and logs `bunq is OFF`.

| Endpoint                         | What it does                                                    |
| -------------------------------- | --------------------------------------------------------------- |
| `GET /api/health`                | the only route without the token                                |
| `GET /api/account`               | whether a key is set, and the account id                        |
| `POST /api/tabs`                 | `{"amountCents", "description"}`, answers the tab's id and link |
| `POST /api/tabs/{id}/cancel`     | cancels a tab; `accepted` is false when bunq refused            |
| `GET /api/tabs/{id}/payments`    | the incoming euro payments on one tab                           |
| `GET /api/payments?count=1..200` | the account's latest incoming euro payments                     |

A bank error is a `502` with bunq's own words as the body, so `steward` can show them unchanged.

## Why it is a process of its own

- **The key has one holder.** `steward` faces the internet; a key in its environment is a key every
  bug there can leak. `:architecture` refuses `com.bunq` outside this
  module, and `TopologyDeploymentTest` refuses the key in any other service's environment.
- **Only `steward` reaches it.** It joins the internal `bank` network, shared with `steward` alone,
  and `bank-egress`, its own way out to bunq. No Minecraft server, no Caddy and no Postgres can
  open a connection to it.
- **It keeps no business state.** No database, no schema, nothing to migrate. The only file it
  writes is bunq's session context in `bunq-context`. bunq binds a context to the device and IP that
  registered it, so a context made fresh on every start would register a new device at bunq each
  time. The context is never copied to another host; a move registers anew.

What crosses the wire is filtered here: only incoming euro payments with an id and a readable time.
The watermark and the already-booked check stay in `steward`, which owns the requests.

## Where things live

- `StewardBunq`: the entry point and the routes, behind `:internal-api`'s server.
- `BunqGateway`: the bunq SDK, and the conversion between its decimals and integer cents.
- `com.bunq.sdk.http.BunqRequestBuilder`: a patched copy of the SDK's class, in the vendor's package.
