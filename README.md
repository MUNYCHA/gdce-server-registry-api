# Server Registry API

A small REST service for keeping a list of servers and testing whether they answer on a TCP
port. An administrator registers a host once; `POST /api/servers/check` then probes every
registered address and reports which ones are reachable and how long each took.

Spring Boot 3.3 · Java 21 · PostgreSQL 15.

---

## Run it locally

Needs Java 21 and a PostgreSQL on `localhost:5432` with a `serverregistry` database:

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem mvn spring-boot:run
curl -fsS localhost:8080/api/servers      # []
```

No configuration required — `application.yml` carries development defaults for everything.

## Run the tests

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem mvn -B test
```

`JAVA_HOME` is not optional: the default JDK on this workstation is Java 8 and a plain
`mvn test` fails to compile. The suite needs a running Docker daemon — the integration test
starts a real PostgreSQL through Testcontainers rather than substituting H2.

---

## The API

Base path `/api/servers`. No authentication — see the warning below.

| Method | Path | Does |
|---|---|---|
| `POST` | `/api/servers` | Register a server → `201` |
| `GET` | `/api/servers` | List all, newest first → `200` |
| `PUT` | `/api/servers/{id}` | Replace a server's fields → `200` |
| `POST` | `/api/servers/check` | Probe every registered server → `200` |
| `GET` | `/api/servers/types` | Distinct server types in use → `200` |
| `GET` | `/api/servers/systems` | Distinct systems in use → `200` |
| `DELETE` | `/api/servers/{id}` | Remove one → `204` |

There is no trailing-slash form: `/api/servers` works, `/api/servers/` returns `404`.
Spring Boot 3 dropped that matching, and it was not added back.

**Register a server.** `port` is optional and defaults to `22`; `serverType` is
upper-cased and trimmed before it is stored, so `db`, `DB` and ` Db ` are one value.
`systemName` groups servers for display (e.g. so a UI can group the registry by
the system a server belongs to) — trimmed but not upper-cased, since it's a display
name rather than a short code.

```bash
curl -X POST localhost:8080/api/servers \
  -H 'Content-Type: application/json' \
  -d '{"hostname":"web-01","ipAddress":"10.0.1.20","serverType":"WEB","systemName":"Billing","port":80}'
```

```json
{
  "id": 1,
  "hostname": "web-01",
  "ipAddress": "10.0.1.20",
  "serverType": "WEB",
  "systemName": "Billing",
  "port": 80,
  "createdAt": "2026-07-31T09:14:02.117Z"
}
```

Registering the same IP and port twice returns `409`. An invalid IP or a blank hostname
returns `400`. `hostname` is a display label and nothing else — it is never resolved, and
checks always connect to `ipAddress`.

**Check reachability.** One TCP connect attempt per server, all of them concurrently, so a
request costs roughly one timeout in total rather than one per server:

```json
[
  { "id": 1, "hostname": "web-01", "ipAddress": "10.0.1.20", "port": 80,
    "reachable": true,  "latencyMs": 12,   "error": null },
  { "id": 2, "hostname": "db-01",  "ipAddress": "10.0.1.30", "port": 5432,
    "reachable": false, "latencyMs": null, "error": "Connection refused" }
]
```

Servers being down is the answer, not a failure: this stays `200` even when nothing is
reachable. Results are never stored — the registry holds servers, not a history of checks.

---

## Deploying

The API ships as a container that connects to a database you already have — it doesn't set
one up or manage it. `DEPLOY.md` is the full guide; short version:

```bash
cp .env.example .env      # fill in DB_URL, DB_USER, DB_PASSWORD for your database
docker compose -f compose.prod.yaml up -d --build
curl -fsS localhost:8080/api/servers      # []
```

Nothing operational is compiled in. Every value in `application.yml` is
`${ENV_VAR:development-default}`, so a deployment overrides the environment and never edits
the file.

> **Do not expose the port publicly.** There is no authentication, and
> `POST /api/servers/check` will TCP-connect to any address in the registry. Published on a
> public interface that is an unauthenticated port scanner pointed at your internal network.
> `compose.prod.yaml` binds to `127.0.0.1` by default; put a reverse proxy or VPN in front
> before widening it.

---

## Layout

```
src/main/java/com/gdce/serverregistry/   11 classes, packaged by capability (server/, reachability/)
src/main/resources/application.yml       all config, env-driven
src/main/resources/schema.sql            CREATE TABLE IF NOT EXISTS, run at startup
Dockerfile  .env.example                 builds and configures the container
compose.prod.yaml                        deploy — connects to a database you provide
deploy/server-registry.service           systemd alternative to Docker
DEPLOY.md                                the deployment guide
server_registry_api.md                   the contract
```

**Read `server_registry_api.md` before changing anything.** It is the specification this
implementation is held to — endpoint behaviour, status codes, validation rules, the fixed
dependency list, and a record of which decisions were made deliberately and why. Several
things that look like oversights are in there as choices: no Flyway, no Actuator, no service
layer for CRUD, and no history table for check results.
