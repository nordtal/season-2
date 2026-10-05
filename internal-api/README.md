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
- `agent` is the agent's half: `AgentWire` (the routes and records, documented in
  [`../steward-agent/README.md`](../steward-agent/README.md)) and `AgentClient`, the only way steward
  reaches Docker and the volumes. A run sees it as `ContainerOps` and `Snapshots`.
- `sse.Follows` keeps a long server-sent event stream alive on the process's scheduler: a heartbeat,
  a check whether the reader may still read, and every follow ended before Jetty stops. The agent's log streams and steward's
  relay of them are both one.

Both services also sit on an internal Docker network shared with `steward` alone;
`TopologyDeploymentTest` holds that shape.

`:architecture` keeps the module to Javalin, Gson, slf4j and `:common`.
