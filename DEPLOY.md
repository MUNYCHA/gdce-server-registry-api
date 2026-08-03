# Deploying

The API ships as a container. It connects to a PostgreSQL you already have — it does not
bring one, set one up, or manage it. `compose.prod.yaml` has no `db` service; that's
deliberate.

## 1. Give the database user `CREATE` privilege

The app runs `schema.sql` at startup (`CREATE TABLE IF NOT EXISTS servers`), so the user in
step 2 needs `CREATE` on the target database. Safe to run on every restart.

## 2. Set the connection

```bash
cp .env.example .env
```

```
DB_URL=jdbc:postgresql://<db-host>:5432/<db-name>
DB_USER=<db-user>
DB_PASSWORD=<db-password>
```

`<db-host>` depends on where Postgres actually runs:

| Where Postgres runs | `<db-host>` |
|---|---|
| On the host directly, or in Docker with a published port | `host.docker.internal` |
| In Docker, no published port | its container name — and this app's container must join that network; ask the admin for both |

## 3. Run it

```bash
docker compose -f compose.prod.yaml up -d --build
```

## 4. Check it

```bash
docker compose -f compose.prod.yaml ps        # "healthy", allow ~60s
curl -fsS localhost:8080/api/servers          # []
```

Redeploying a new version is steps 3–4 again, after `git pull`.

---

**Do not expose the port publicly.** There is no authentication, and
`POST /api/servers/check` will TCP-connect to any address in the registry — on a public
interface that's an unauthenticated port scanner pointed at your network. The compose file
binds to `127.0.0.1` by default (`APP_BIND`); put a reverse proxy or VPN in front before
widening it.

**If it won't start**, `docker compose -f compose.prod.yaml logs -f app`:

| In the logs | Cause |
|---|---|
| `Connection refused` | DB not reachable at `DB_URL` |
| `no pg_hba.conf entry for host` | DB is reachable but doesn't allow this host/subnet |
| `password authentication failed` | `DB_USER` / `DB_PASSWORD` wrong |
| `missing table [servers]` | `DB_USER` lacks `CREATE` |

## Other ways to run it

- **systemd** — runs the jar directly, no Docker. See `deploy/server-registry.service`.
  Same three env vars, but set in `/etc/server-registry.env`, and `DB_URL` should point at
  `localhost` rather than `host.docker.internal` since there's no container in the way.
