# steward-bunq

The one process that holds the bank key. It creates and cancels bunq.me tabs and lists incoming
payments, and it decides nothing: which request a payment pays, whether it is late, and what access
it buys are `steward`'s and the bot's. It reads `NORDTAL_STEWARD_BUNQ_API_KEY` and
`NORDTAL_STEWARD_BUNQ_ACCOUNT_ID` from its own `secrets.env`, which steward-agent never mounts (see
[`deploy/README.md`](../deploy/README.md#first-deployment)). Without them it still starts, says
`configured: false` on `/api/account` and `503` on every other bank route, and logs `bunq is OFF`.

| Endpoint                         | What it does                                                    |
| -------------------------------- | --------------------------------------------------------------- |
| `GET /api/health`                | the only route without the token                                |
| `GET /api/account`               | whether a key is set, and the account id                        |
| `POST /api/tabs`                 | `{"amountCents", "description"}`, answers the tab's id and link |
| `POST /api/tabs/{id}/cancel`     | cancels a tab; `accepted` is false when bunq refused            |
| `GET /api/tabs/{id}/payments`    | the incoming euro payments on one tab                           |
| `GET /api/payments?count=1..200` | the account's latest incoming euro payments                     |

A bank error is a `502` with bunq's own words as the body, so `steward` can show them unchanged.

## Boundaries

- **One holder of the key.** `:architecture` refuses `com.bunq` outside this module, and
  `TopologyDeploymentTest` refuses the key in any service's environment, any interpolation of it in
  `compose.yml` and a mount of this service's secrets anywhere else.
- **Only `steward` reaches it**, over the internal `bank` network shared with `steward` alone; its own
  way out to bunq is `bank-egress`.
- **No business state.** No database and no schema; the one file it writes is bunq's session context
  in `bunq-context`, which is bound to the device and IP that registered it and never copied to another host.
- **Filtered here.** Only incoming euro payments with an id and a readable time cross the wire; the
  watermark and the already-booked check stay in `steward`.

## Where things live

- `StewardBunq`: the entry point and the routes, behind `:internal-api`'s server.
- `BunqGateway`: the bunq SDK, and the conversion between its decimals and integer cents.
- `com.bunq.sdk.http.BunqRequestBuilder`: a patched copy of the SDK's class, in the vendor's package.
