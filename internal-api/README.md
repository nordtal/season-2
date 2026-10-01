# internal-api

The HTTP wire between `steward` and the services only it may call, `steward-agent` and
`steward-bunq`. One server, one client, one gate, so each service runs the same token check and
`steward` reads every failure the same way.

- `InternalServer` reads `NORDTAL_<SERVICE>_PORT` and `NORDTAL_<SERVICE>_TOKEN`, refuses to start
  without a token, and answers `401` on every `/api/*` route but `/api/health` unless
  `X-Steward-Token` matches. The comparison is constant time. `serve` also keeps `common`'s
  readiness marker fresh, which is what the container's healthcheck reads: the image has no HTTP
  client, and every JVM service is then judged healthy the same way.
- `InternalClient` is what `steward` holds per service. A refusal arrives as a `Failure` naming the
  service, so an error page can say which one answered: `504` on a timeout, `502` when nothing
  answered at all.
- `BankWire` is the bank's half of the wire: the routes and the records that cross it. Amounts are
  integer cents and times ISO instants, so neither end needs the bank's SDK to read them.

The token is a second fence, not the only one. Both services sit on an internal Docker network
shared with `steward` alone (see `compose.yml`), and `TopologyDeploymentTest` holds that shape.

Why a module of its own: the gate existed in `steward-agent` and was about to be written again for
`steward-bunq`. `:architecture` keeps it to Javalin, Gson and `:common`, so a service that depends
on it takes nothing else along.
