# Deploying the server registry

Two supported paths. **Docker Compose** is the primary one — it brings its own PostgreSQL
and needs nothing on the host but Docker. **systemd** runs the jar directly against a
database you provide.

Both read identical environment variables, so moving between them is a config change, not a
code change. Nothing operational is compiled in: `application.yml` holds development
defaults and every value in it can be overridden from the environment.

---

## Before either path: run the tests

Neither deployment runs the test suite. The Docker build cannot — the integration test
starts a PostgreSQL container of its own, and there is no Docker daemon inside an image
build. Run it yourself, on a machine that has Docker:

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem mvn -B test
```

`JAVA_HOME` is not optional. The default JDK on this workstation is Java 8 and a plain
`mvn test` fails to compile.

---

## Path A — Docker Compose (recommended)

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

## Path B — systemd

Use this when the host already has PostgreSQL, or when Docker is not an option.

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
sudo install -m 600 -o root -g root deploy/server-registry.env.example /etc/server-registry.env
sudo editor /etc/server-registry.env      # DB_URL, DB_USER, DB_PASSWORD
sudo cp deploy/server-registry.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now server-registry
```

**5. Verify:**

```bash
systemctl status server-registry
journalctl -u server-registry -f
curl -fsS localhost:8080/api/servers
```

Unlike the compose path, a missing value in `/etc/server-registry.env` does not fail the
start — the application quietly falls back to `localhost` and `postgres`/`postgres`. Check
`journalctl` for the datasource it actually connected to after the first deploy.

To deploy a new version: replace `app.jar` and `systemctl restart server-registry`.

### Resource limits

The unit sets `MemoryMax=768M` and `CPUQuota=100%` (one core), matching the compose defaults.
These are cgroup limits enforced by systemd, so unlike the compose path they are edited in
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
| `DB_URL` | `jdbc:postgresql://localhost:5432/serverregistry` | JDBC URL. Compose sets this to the `db` service automatically. |
| `DB_USER` | `postgres` | Database user. Required by compose. |
| `DB_PASSWORD` | `postgres` | Database password. Required by compose. |
| `DB_NAME` | `serverregistry` | Compose only — names the database and feeds `DB_URL`. |
| `SERVER_PORT` | `8080` | Port the JVM listens on. In compose, map ports instead. |
| `APP_BIND` | `127.0.0.1` | Compose only — host interface to publish on. |
| `APP_PORT` | `8080` | Compose only — host port. |
| `HEALTHCHECK_TIMEOUT_MS` | `3000` | Per-server TCP connect timeout for `POST /api/servers/check`. |
| `APP_MEMORY_LIMIT` | `768m` | Compose only — container limit; JVM heap follows at 75%. |
| `APP_CPU_LIMIT` | `1.0` | Compose only — cores for the app. `1.0` is one core. |
| `DB_MEMORY_LIMIT` | `512m` | Compose only — container limit for PostgreSQL. |
| `DB_CPU_LIMIT` | `1.0` | Compose only — cores for PostgreSQL. |
| `LOG_LEVEL` | `INFO` | Root logger level. |

`SPRING_*` variables work too, via Spring's relaxed binding — `SPRING_DATASOURCE_URL`
overrides `DB_URL` if both are set. Prefer the names above; they are the documented surface.

---

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
