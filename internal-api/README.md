# internal-api

`internal-api` is the HTTP wire between `steward` and the two services only it calls, `steward-agent`
and `steward-bunq`. Both services use one server and `steward` uses one client, so every service
checks the token the same way and `steward` reads every failure the same way.

- `InternalServer` reads `NORDTAL_<SERVICE>_PORT` and `NORDTAL_<SERVICE>_TOKEN`. It refuses to start
  without a token. It answers `401` on every `/api/*` route except `/api/health` unless
  `X-Steward-Token` matches, and compares the token in constant time. `serve` also keeps the
  readiness marker of `common` fresh. The container's health check reads the marker, because the
  image has no HTTP client.
- `InternalClient` is the client that `steward` holds per service. A refusal arrives as a `Failure`
  that names the service. A timeout is a `504`, and no answer at all is a `502`.
- `BankWire` holds the routes and records of `steward-bunq`. Amounts are integer cents and times are
  ISO instants.
- `agent` holds `AgentWire`, the routes and records of `steward-agent` (see
  [`../steward-agent/README.md`](../steward-agent/README.md)), and `AgentClient`, the only way that
  `steward` reaches Docker and the volumes.
- `sse.Follows` keeps a long server-sent event stream alive on the process's scheduler. It sends a
  heartbeat, checks that the reader may still read, and ends every follow before Jetty stops.

Both services sit on an internal Docker network that only `steward` shares. `TopologyDeploymentTest`
holds that.

`:architecture` limits the module to Javalin, Gson, slf4j and `:common`.
