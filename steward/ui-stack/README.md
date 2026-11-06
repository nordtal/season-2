# Steward UI stack

One `docker compose` stack for work on Steward's interface. It needs Docker and nothing else: no JDK,
no Node and no local stack.

```bash
docker compose up -d       # in this directory; the first start builds Steward and takes minutes
docker compose logs -f     # follow the build and the servers
docker compose down        # stop; the volumes keep the build caches, the data is gone
```

Then open http://localhost:5173. The page is already signed in.

| service    | what it is                                                                                    | port              |
| ---------- | --------------------------------------------------------------------------------------------- | ----------------- |
| `postgres` | A throwaway server in memory. Every start of `preview` drops what the last one left.          | `127.0.0.1:15432` |
| `preview`  | `:steward:preview` from this checkout: the real interface and API, over stand-ins.            | `127.0.0.1:18180` |
| `vite`     | Vite with hot reload. It proxies `/api` and `/auth` to `preview` and adds its session cookie. | `127.0.0.1:5173`  |

- **A change under `frontend/src/`** shows at once.
- **A change of Java code** needs `docker compose restart preview`. The build is incremental.
- **A change of `package-lock.json`** needs `docker compose restart preview vite`. Gradle's
  `npmInstall` fills the `node-modules` volume.
- **Data from a dump**: put a `pg_dump -Fc` file into `dumps/` (or point `STEWARD_UI_DUMPS` at its
  directory) and start with `STEWARD_UI_DUMP=<file> docker compose up -d`.
- **psql**: `docker compose exec postgres psql -U postgres`. The preview's database is the newest
  `test_<n>`.

The preview signs in as an invented admin whose key counts as freshly held, so step-up dialogs pass.
The stand-in agent shows the services of `compose.yml`, and none of them run. Nothing reaches
Discord, bunq or a Docker daemon. `StewardPreview` in the steward tests holds the details.

The build writes only into volumes, so the checkout's own `build/` and `node_modules/` stay those
of the host. `STEWARD_UI_PORT` and `STEWARD_UI_POSTGRES_PORT` move the published ports.
