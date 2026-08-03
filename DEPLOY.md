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

### systemd

Runs the jar directly, no Docker. `deploy/server-registry.service` expects the pieces
below already in place — none of this happens automatically.

1. Build the jar and place it where the unit expects. Maven names it
   `server-registry-<version>.jar`; the unit hardcodes `app.jar`, so rename it on the way in:

   ```bash
   JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem mvn -B -DskipTests package
   sudo mkdir -p /opt/server-registry
   sudo cp target/server-registry-*.jar /opt/server-registry/app.jar
   sudo cp server_registry_api.md /opt/server-registry/server_registry_api.md
   ```

2. Create the system user the unit runs as (`User=serverregistry` /
   `Group=serverregistry`) and hand it the directory:

   ```bash
   sudo useradd --system --no-create-home --shell /usr/sbin/nologin serverregistry
   sudo chown -R serverregistry:serverregistry /opt/server-registry
   ```

3. Set the connection — same three env vars as step 2 above, but as a root-owned,
   `0600` file at `/etc/server-registry.env` (the unit's `EnvironmentFile`), and `DB_URL`
   should point at `localhost` rather than `host.docker.internal` since there's no
   container in the way:

   ```bash
   sudo cp .env.example /etc/server-registry.env
   sudo $EDITOR /etc/server-registry.env   # DB_URL=jdbc:postgresql://localhost:5432/<db-name>, DB_USER, DB_PASSWORD
   sudo chown root:root /etc/server-registry.env
   sudo chmod 0600 /etc/server-registry.env
   ```

4. Install and start the unit:

   ```bash
   sudo cp deploy/server-registry.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now server-registry
   ```

5. Check it:

   ```bash
   sudo systemctl status server-registry     # active (running)
   curl -fsS localhost:8080/api/servers      # []
   ```

Redeploying a new version: repeat step 1's build-and-copy, then
`sudo systemctl restart server-registry`.
