# Deploying the server registry

Three supported paths, differing only in where PostgreSQL lives and whether the app runs in
a container.

| Path | App | Database | Use when |
|---|---|---|---|
| **A** `compose.prod.yaml` | container | already a service on the host | production — the host runs its own PostgreSQL |
| **B** `compose.yaml` | container | container, bundled | a self-contained host, or local development |
| **C** systemd | jar on the host | you provide | no Docker, or you want the jar under systemd |

All three read identical environment variables, so moving between them is a config change,
not a code change. Nothing operational is compiled in: `application.yml` holds development
defaults and every value in it can be overridden from the environment.

---

## Before any path: run the tests

Neither deployment runs the test suite. The Docker build cannot — the integration test
starts a PostgreSQL container of its own, and there is no Docker daemon inside an image
build. Run it yourself, on a machine that has Docker:

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem mvn -B test
```

`JAVA_HOME` is not optional. The default JDK on this workstation is Java 8 and a plain
`mvn test` fails to compile.

---

## Path A — Docker Compose against the host's PostgreSQL

Only the API runs in a container; PostgreSQL is an ordinary service on the same server.
Uses `compose.prod.yaml`, which has no `db` service — nothing in it creates or migrates a
database, so one must already exist.

### The one thing that catches people

Inside a container, `localhost` is the container, not the host, so `localhost:5432` does not
reach a PostgreSQL running beside it. `compose.prod.yaml` maps `host.docker.internal` to the
bridge gateway, and `DB_URL` uses that name:

```
DB_URL=jdbc:postgresql://host.docker.internal:5432/serverregistry
```

A default PostgreSQL install listens on loopback only and rejects the bridge, so both of
these need changing once:

```bash
ip -4 addr show docker0                      # the gateway, usually 172.17.0.1
sudo -u postgres psql -c "SHOW listen_addresses;"
```

| File | Change |
|---|---|
| `postgresql.conf` | `listen_addresses = 'localhost,172.17.0.1'` |
| `pg_hba.conf` | `host  serverregistry  serverregistry  172.16.0.0/12  scram-sha-256` |

```bash
sudo systemctl reload postgresql
```

Confirm the subnet on your own server rather than copying `172.17.0.1` — Compose creates its
own network and the range can differ.

### Create the database

```sql
CREATE USER serverregistry WITH PASSWORD 'the-generated-password';
CREATE DATABASE serverregistry OWNER serverregistry;
```

Ownership is not cosmetic: the app runs `schema.sql` at startup and needs `CREATE` on the
database. The `servers` table is `CREATE TABLE IF NOT EXISTS`, so this is safe on every
restart.

### Configure and deploy

`.env` needs three values here, not two — with the database outside Compose there is no
service name to build `DB_URL` from:

```bash
cat > .env <<'EOF'
DB_URL=jdbc:postgresql://host.docker.internal:5432/serverregistry
DB_USER=serverregistry
DB_PASSWORD=
EOF
openssl rand -base64 24        # paste into DB_PASSWORD
editor .env
```

Before deploying, prove the credentials work *from inside a container* — this catches the
listen/pg_hba mistakes above, which a `psql` on the host will not:

```bash
docker run --rm --add-host host.docker.internal:host-gateway postgres:15 \
  psql "postgresql://serverregistry:PASSWORD@host.docker.internal:5432/serverregistry" -c 'select 1'
```

Then:

```bash
docker compose -p registry-prod -f compose.prod.yaml up -d --build
```

The explicit `-p` matters when both compose files sit in one directory: Compose otherwise
derives the same project name for each, and running this one would treat Path B's `db`
container as an orphan.

Verify:

```bash
docker compose -p registry-prod -f compose.prod.yaml ps      # "healthy", allow ~60s
docker compose -p registry-prod -f compose.prod.yaml logs -f app
curl -fsS localhost:8080/api/servers                         # []
```

All three of `DB_URL`, `DB_USER` and `DB_PASSWORD` are required — Compose refuses to start
without them, so the stack cannot come up on the application's development defaults and
silently connect somewhere unintended.

| Task | Command (all take `-p registry-prod -f compose.prod.yaml`) |
|---|---|
| Deploy a new version | `git pull && docker compose … up -d --build` |
| Restart | `docker compose … restart app` |
| Logs | `docker compose … logs -f app` |
| Stop | `docker compose … down` |

`down -v` is harmless here — the data is on the host, not in a Compose volume. Backups are
an ordinary `pg_dump` run on the host, and nothing in this repo schedules them.

### If it will not start

The app exits at startup when it cannot reach the database and is restarted by
`restart: unless-stopped` until it can. A crash loop is the expected symptom, not a
misconfiguration of Compose:

| In the logs | Cause |
|---|---|
| `Connection refused` | PostgreSQL not running, or not listening on the bridge address |
| `no pg_hba.conf entry for host` | listening, but the docker subnet is not allowed |
| `password authentication failed` | `DB_USER` / `DB_PASSWORD` wrong |
| `missing table [servers]` | the user lacks `CREATE`, so `schema.sql` did not run |

---

## Path B — Docker Compose with a bundled PostgreSQL

Brings its own database in a second container and needs nothing on the host but Docker.
Good for a self-contained host or local development; use Path A when the server already
runs PostgreSQL.

On the server, with the repository checked out:

```bash
cp .env.example .env
openssl rand -base64 24        # paste into DB_PASSWORD
editor .env
docker compose up -d --build
```

Verify:

```bash
docker compose ps                       # both services "healthy"
curl -fsS localhost:8080/api/servers    # []
docker compose logs -f app
```

Compose refuses to start if `DB_USER` or `DB_PASSWORD` is unset, so the stack cannot come
up on the application's `postgres`/`postgres` development defaults by accident.

| Task | Command |
|---|---|
| Deploy a new version | `git pull && docker compose up -d --build` |
| Restart | `docker compose restart app` |
| Logs | `docker compose logs -f app` |
| Stop, keep data | `docker compose down` |
| Stop, **delete the database** | `docker compose down -v` |
| psql shell | `docker compose exec db psql -U "$DB_USER" -d "$DB_NAME"` |

The database has no published port — it is reachable from the app container and nowhere
else. Its data lives in the `pgdata` volume and survives `down` and rebuilds.

### Resource limits

Both services are capped, so neither can take the host down with it. Defaults are one core
and 768 MB for the app, one core and 512 MB for the database; all four are `.env` variables.
Raising `APP_MEMORY_LIMIT` also raises the JVM heap, which is sized at 75% of the container
limit rather than by a fixed `-Xmx` — there is no second number to keep in step with it.

```bash
docker stats --no-stream          # actual usage against the limits
```

A container killed for exceeding its memory limit exits `137`. If that happens to `app`,
raise `APP_MEMORY_LIMIT`; the idle floor is around 300 MB and almost all of it is JVM
baseline rather than anything that grows with the size of the registry.

### Backups

Nothing here schedules them. One table, so a plain dump is enough:

```bash
docker compose exec -T db pg_dump -U "$DB_USER" serverregistry > registry-$(date +%F).sql
```

---

## Path C — systemd

Use this when Docker is not an option, or when you want the jar supervised by systemd
directly. Like Path A it runs against a PostgreSQL you provide, but with no container in
between — so none of the bridge-networking setup above applies, and `localhost` means what
you expect.

**1. Build the jar** (on a build machine, not necessarily the server):

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem mvn -B -DskipTests package
```

**2. Prepare the server** — Java 21 and a service account:

```bash
sudo apt-get install -y openjdk-21-jre-headless
sudo useradd --system --home /opt/server-registry --shell /usr/sbin/nologin serverregistry
sudo install -d -o serverregistry -g serverregistry /opt/server-registry
```

**3. Create the database:**

```sql
CREATE USER serverregistry WITH PASSWORD 'the-password-from-step-4';
CREATE DATABASE serverregistry OWNER serverregistry;
```

The `servers` table itself is created by the application at startup from
`src/main/resources/schema.sql`, which is `CREATE TABLE IF NOT EXISTS` — safe on every
restart.

**4. Install the unit and its environment file:**

```bash
scp target/server-registry-*.jar server:/tmp/app.jar
sudo install -o serverregistry -g serverregistry /tmp/app.jar /opt/server-registry/app.jar
sudo install -m 600 -o root -g root .env.example /etc/server-registry.env
sudo editor /etc/server-registry.env
sudo cp deploy/server-registry.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now server-registry
```

`.env.example` is shared with the compose paths, so **`DB_URL` needs changing here** — it
ships pointing at `host.docker.internal`, which is how a container reaches the host and
means nothing to a jar running on the host itself:

```
DB_URL=jdbc:postgresql://localhost:5432/serverregistry
```

systemd parses this file itself rather than through a shell: no quotes, no variable
expansion, and no trailing comments on a value line.

**5. Verify:**

```bash
systemctl status server-registry
journalctl -u server-registry -f
curl -fsS localhost:8080/api/servers
```

Unlike the compose paths, a missing value in `/etc/server-registry.env` does not fail the
start — the application quietly falls back to `localhost` and `postgres`/`postgres`. Check
`journalctl` for the datasource it actually connected to after the first deploy, and
confirm what systemd actually passed with
`systemctl show -p Environment server-registry`.

**This path does not bind to loopback.** A Docker port mapping can publish on `127.0.0.1`
alone; systemd cannot, and the JVM listens on every interface. `APP_BIND` does nothing here.
On an API with no authentication, that means port 8080 must be closed at the firewall and
reached through a reverse proxy on the same host — it is not protected by default the way
the compose paths are.

To deploy a new version: replace `app.jar` and `systemctl restart server-registry`.

### Resource limits

The unit sets `MemoryMax=768M` and `CPUQuota=100%` (one core), matching the compose defaults.
These are cgroup limits enforced by systemd, so unlike the compose paths they are edited in
the unit file rather than in the environment file:

```bash
sudo systemctl edit server-registry     # override MemoryMax / CPUQuota
systemctl show server-registry -p MemoryMax -p CPUQuota
```

`MemoryMax` is not optional decoration — `ExecStart` passes `-XX:MaxRAMPercentage=75`, which
needs a limit to take a percentage of. Remove `MemoryMax` and the JVM falls back to sizing
its heap against total host RAM.

---

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/serverregistry` | JDBC URL. **Required in Path A** — set it to `host.docker.internal:5432`. Path B builds it from the `db` service automatically. |
| `DB_USER` | `postgres` | Database user. Required by both compose files. |
| `DB_PASSWORD` | `postgres` | Database password. Required by both compose files. |
| `DB_NAME` | `serverregistry` | Path B only — names the bundled database and feeds its `DB_URL`. Path A puts the name in `DB_URL` instead. |
| `SERVER_PORT` | `8080` | Port the JVM listens on. In compose, map ports instead. |
| `APP_BIND` | `127.0.0.1` | Compose only — host interface to publish on. Ignored under systemd, which always listens on all interfaces. |
| `APP_PORT` | `8080` | Compose only — host port. |
| `HEALTHCHECK_TIMEOUT_MS` | `3000` | Per-server TCP connect timeout for `POST /api/servers/check`. |
| `APP_MEMORY_LIMIT` | `768m` | Compose only — container limit; JVM heap follows at 75%. |
| `APP_CPU_LIMIT` | `1.0` | Compose only — cores for the app. `1.0` is one core. |
| `DB_MEMORY_LIMIT` | `512m` | Path B only — container limit for the bundled PostgreSQL. |
| `DB_CPU_LIMIT` | `1.0` | Path B only — cores for the bundled PostgreSQL. |
| `LOG_LEVEL` | `INFO` | Root logger level. |

`SPRING_*` variables work too, via Spring's relaxed binding — `SPRING_DATASOURCE_URL`
overrides `DB_URL` if both are set. Prefer the names above; they are the documented surface.

---

## What has to be running

Only the database. The app cannot start without one it can reach and authenticate to —
everything below that line is a choice, not a dependency:

| Required | Symptom if missing |
|---|---|
| Docker daemon | nothing starts |
| PostgreSQL (host service in Path A/C, container in Path B) | app crash-loops at startup |
| PostgreSQL listening on the bridge address (Path A) | `Connection refused` despite it running |
| `pg_hba.conf` allowing the docker subnet (Path A) | `no pg_hba.conf entry for host` |

| Optional | What it actually changes |
|---|---|
| Reverse proxy | Only needed if something *off* the server must reach the API. Bound to `127.0.0.1`, it already works for anything on the host. |
| Egress to registered addresses | Only changes whether checks report `true`. Without it `POST /api/servers/check` still returns `200`, with every server `reachable: false`. |

Those two stop being optional together: the moment a proxy widens `APP_BIND` past
`127.0.0.1`, the missing authentication below stops being theoretical.

## Two things to get right in production

**Do not expose the port publicly.** There is no authentication (contract §13), and
`POST /api/servers/check` will TCP-connect to any address in the registry. Published on a
public interface, that is an unauthenticated port scanner with a view into your internal
network. The compose default binds to `127.0.0.1`; put nginx, Caddy or a VPN in front, and
only widen `APP_BIND` once something else authenticates the caller.

**Outbound access is a feature, not an oversight.** The host must be able to reach every
address you register, or every check reports unreachable. Firewall rules and security
groups need egress to those hosts and ports. The systemd unit deliberately omits
`IPAddressDeny` for the same reason — restrict it at the network layer, where you can
express which targets are legitimate.

## Not included

Deliberately, matching contract §13:

- **No CI configuration.** Build and test are the commands above.
- **No migration tool.** `schema.sql` is idempotent and one table, so a single instance is
  fine. Before running a second app container against one database, move the DDL to Flyway
  and set `spring.sql.init.mode=never` — two instances starting together would otherwise
  race on it.
- **No horizontal scaling, TLS termination or log shipping.** Reverse proxy territory.
