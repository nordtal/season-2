# steward-bunq

`steward-bunq` is the only process that holds the bank key. It creates and cancels bunq.me tabs and
lists incoming payments. It decides nothing: `steward` and the bot decide which request a payment
pays, whether it is late and what access it buys.

It reads `NORDTAL_STEWARD_BUNQ_API_KEY` and `NORDTAL_STEWARD_BUNQ_ACCOUNT_ID` from its own
`secrets.env`, which steward-agent never mounts (see
[`deploy/README.md`](../deploy/README.md#first-deployment)). Without them it starts, answers
`configured: false` on `/api/account` and `503` on every other bank route, and logs `bunq is OFF`.

Only `GET /api/health` needs no token. A bank error is a `502` with bunq's own words as the body.

## Boundaries

- `:architecture` refuses `com.bunq` outside this module.
- `TopologyDeploymentTest` refuses the key in any other service's environment, any interpolation of
  it in `compose.yml` and a mount of this service's secrets anywhere else.
- Only `steward` reaches this service, over the internal network `bank`. The service reaches bunq
  over `bank-egress`.
- The service has no database. It writes one file, bunq's session context in `bunq-context`. bunq
  binds that file to the device and IP that registered it, so it is never copied to another host.
- The service returns only incoming euro payments that have an id and a readable time. `steward`
  keeps the watermark and the check for an already booked payment.

## Classes

- `StewardBunq` is the entry point and holds the routes, served by `:internal-api`'s server.
- `BunqGateway` calls the bunq SDK and converts its decimals to integer cents.
- `com.bunq.sdk.http.BunqRequestBuilder` is a patched copy of the SDK class of that name.
