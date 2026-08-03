# Server Registry & Reachability API — Contract

An internal admin API. Admins register servers (hostname, IP, type, port) and can
press one button to test whether every registered server is reachable.

This document is the spec. Build exactly what is here — see **Non-goals** before
adding anything.

---

## 1. Tech stack

| | |
|---|---|
| Language | Java 21 (virtual threads required) |
| Framework | Spring Boot 3.3.x |
| Database | PostgreSQL 15+ |
| Build | Maven |

Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
`spring-boot-starter-validation`, `postgresql`.

Test scope only: `spring-boot-testcontainers`, `testcontainers:postgresql`,
`testcontainers:junit-jupiter`. These give the integration test (§12) a real
database; nothing they provide reaches the running application.

No Flyway, no Liquibase, no security starter, no Lombok. Schema is created from
`schema.sql` on startup.

No `spring-boot-starter-actuator` either, which the dependency list above already
implies but deployment makes tempting: a container healthcheck wants something to
poll. It polls `GET /api/servers/types` instead — that exercises the web layer,
JPA and a real database round trip, so it fails for the same reasons a readiness
probe would, without widening the dependency list (§14).

---

## 2. Design principles

1. **Keep it small.** Five endpoints, one table, ~8 classes. No service layer for
   CRUD — the controller calls the repository directly.
2. **Reachability is transient.** Check results are computed on demand and
   returned. Nothing is persisted. There is no history table.
3. **The IP is what we connect to.** `hostname` is a human-readable display
   label only. It is never resolved, never used for the connection.
4. **Records over classes** for all DTOs.

---

## 3. Database schema

`src/main/resources/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS servers (
    id          BIGSERIAL PRIMARY KEY,
    hostname    VARCHAR(255) NOT NULL,
    ip_address  VARCHAR(45)  NOT NULL,
    server_type VARCHAR(30)  NOT NULL,
    port        INT          NOT NULL DEFAULT 22,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_servers_ip_port UNIQUE (ip_address, port),
    CONSTRAINT ck_servers_port CHECK (port BETWEEN 1 AND 65535)
);
```

Notes:
- `hostname` is a display label. **No unique constraint** — duplicates allowed.
- `ip_address` is `VARCHAR(45)` to accommodate IPv6 string form.
- `created_at` is set by the database. The application never writes it.
- Entity must use `jakarta.persistence` annotations and map to this table
  exactly. Set `spring.jpa.hibernate.ddl-auto=validate`.

### server_type is free text

Not an enum. Admins type whatever they want — `DB`, `WEB`, `MOBILE`, or
something nobody anticipated. The entity field is a plain `String`.

**Normalise on the way in**: trim whitespace and uppercase the value. `db`,
` DB `, and `Db` all persist as `DB`. This is the only transformation applied to
any incoming field, and it exists to stop the column degrading into near-
duplicates over time.

Do it in the compact constructor of the `ServerRequest` record, which runs
during deserialisation and therefore *before* validation. A whitespace-only
`serverType` collapses to `""` and is then rejected by `@NotBlank`, which is the
behaviour §12 requires — normalising after validation would let `"   "` through.

Existing values are exposed through `GET /api/servers/types` (§4.5) so the form
can suggest them without restricting input.

---

## 4. Endpoints

Base path: `/api/servers`

### 4.1 Create — `POST /api/servers`

Request:

```json
{
  "hostname": "prod-db-01",
  "ipAddress": "10.0.1.15",
  "serverType": "DB",
  "port": 5432
}
```

`port` is optional. When omitted or null, default to **22** in the application
before saving (do not rely solely on the DB default).

Response `201 Created`:

```json
{
  "id": 1,
  "hostname": "prod-db-01",
  "ipAddress": "10.0.1.15",
  "serverType": "DB",
  "port": 5432,
  "createdAt": "2026-07-28T09:12:03Z"
}
```

`createdAt` is whatever precision the database column carries, serialised as
ISO-8601. Postgres `TIMESTAMPTZ` gives microseconds, so expect
`2026-07-29T03:21:33.388176Z` rather than the whole seconds shown above. Do not
truncate it — clients must parse ISO-8601 properly rather than assume a width.

Errors: `400` validation failure, `409` duplicate `(ipAddress, port)`.

### 4.2 List — `GET /api/servers`

No parameters. Returns a JSON array of the same object shape as 4.1, ordered by
`createdAt` descending (newest first). Empty array when there are no rows.
No pagination.

The ordering comes from `ServerRepository.NEWEST_FIRST`, the shared constant
described in §8 — not from a sort declared here.

Response: `200 OK`

### 4.3 Delete — `DELETE /api/servers/{id}`

Response: `204 No Content`. Returns `404` if the id does not exist.

### 4.4 Check all — `POST /api/servers/check`

No request body. Tests every registered server and returns the results.

Response `200 OK`:

```json
[
  {
    "id": 1,
    "hostname": "prod-db-01",
    "ipAddress": "10.0.1.15",
    "port": 5432,
    "reachable": true,
    "latencyMs": 12,
    "error": null
  },
  {
    "id": 2,
    "hostname": "old-web",
    "ipAddress": "10.0.2.9",
    "port": 80,
    "reachable": false,
    "latencyMs": null,
    "error": "connect timed out"
  }
]
```

Field rules:
- `reachable: true` → `latencyMs` is set, `error` is null.
- `reachable: false` → `latencyMs` is null, `error` holds the exception message.
- Result order matches the order returned by `GET /api/servers`. Both load
  through `ServerRepository.NEWEST_FIRST` (§8), so the two cannot drift apart.
- Empty registry returns `[]`.

**This endpoint returns `200` even when every server is unreachable.** Servers
being down is the answer, not an error condition. Only application or database
failure produces a 5xx.

### 4.5 Distinct types — `GET /api/servers/types`

Returns every `server_type` value currently in use, so the admin form can offer
them as suggestions (an HTML `<datalist>`) while still accepting new values.

```json
["APP", "DB", "MOBILE", "WEB"]
```

Backed by a single derived query on the repository:

```java
@Query("SELECT DISTINCT s.serverType FROM Server s ORDER BY s.serverType")
List<String> findDistinctServerTypes();
```

Empty registry returns `[]`. Response: `200 OK`.

---

## 5. Validation

Applied to the `POST` request record with Bean Validation:

| Field | Rules |
|---|---|
| `hostname` | `@NotBlank`, `@Size(max = 255)` |
| `ipAddress` | `@NotBlank`, `@Size(max = 45)`, `@Pattern` matching IPv4 or IPv6 |
| `serverType` | `@NotBlank`, `@Size(max = 30)` — free text, uppercased on save |
| `port` | nullable; when present `@Min(1) @Max(65535)` |

The IP pattern must reject invalid octets such as `10.0.1.256`. A typo saved
successfully would surface later as a permanently unreachable server, which is a
confusing way to discover it.

---

## 6. Error response format

All 4xx responses use one shape:

```json
{
  "timestamp": "2026-07-28T09:12:03Z",
  "status": 400,
  "message": "Validation failed",
  "errors": ["ipAddress: must be a valid IP address"]
}
```

`errors` is omitted for non-validation failures, and sorted when present so the
array is deterministic and can be asserted on directly.

Handled in a single `@RestControllerAdvice`:

| Exception | Status |
|---|---|
| `MethodArgumentNotValidException` | 400 — message: "Validation failed", with `errors` |
| `HttpMessageNotReadableException` | 400 — message: "Malformed request body" |
| `DataIntegrityViolationException` | 409 — message: "A server with this IP and port already exists" |
| `NoSuchElementException` (or custom not-found) | 404 — message: "No server with id {id}" |

`HttpMessageNotReadableException` is what Jackson raises for a body it cannot
bind at all — `{"port": "abc"}`, or truncated JSON. It happens before validation
runs, so without this handler those requests would return Spring's default error
body and break the rule that *all* 4xx share one shape.

---

## 7. Health check algorithm

`HealthCheckService.checkAll()` runs four steps **in this order**:

1. **Load** all servers from the repository. The transaction opens and closes
   here, and nowhere else.
2. **Fan out** — submit one task per server to
   `Executors.newVirtualThreadPerTaskExecutor()`. Each task opens a
   `java.net.Socket` and calls
   `connect(new InetSocketAddress(ipAddress, port), timeoutMs)`, measuring
   elapsed time with `System.nanoTime()`.
3. **Collect** — gather all results. Using the executor in try-with-resources
   makes `close()` block until every task completes.
4. **Return** the assembled list.

There is no step 5. Nothing is written back to the database.

Per-task rules:
- Success → `reachable = true`, `latencyMs` = elapsed milliseconds.
- Any `Exception` → `reachable = false`, `error` = the exception message, and the
  task must still return a result. **A failing probe must never propagate an
  exception out of the task** — one dead server cannot fail the whole request.
- Some socket exceptions carry a null or blank message. Fall back to the
  exception's simple class name, so `error` is never null on an unreachable
  server — `"error": null` next to `"reachable": false` tells an admin nothing.
- The socket must be closed in all paths (try-with-resources on the `Socket`).

Collecting the futures unwraps `ExecutionException`. Because `probe` handles its
own failures a task cannot complete exceptionally, so if one somehow does, throw
rather than reporting a server unreachable on false evidence — that is genuine
application failure and the 5xx is correct.

Because probes run concurrently, wall-clock time is roughly the configured
timeout when anything is down, and near-instant when everything is up. It does
not grow with the number of servers.

---

## 8. Project structure

```
src/main/java/com/example/serverregistry/
├── ServerRegistryApplication.java
├── Server.java                  entity
├── ServerRepository.java        JpaRepository + one @Query + the shared Sort
├── ServerRequest.java           record, validation annotations
├── ServerResponse.java          record
├── CheckResult.java             record
├── ServerController.java        all five endpoints
├── HealthCheckService.java      the only class with real logic
└── GlobalExceptionHandler.java

src/main/resources/
├── application.yml
└── schema.sql
```

`ServerController` calls `ServerRepository` directly for the three CRUD
endpoints and delegates only `POST /check` to `HealthCheckService`. Do not
create a `ServerService` — at this size it would only forward calls.

The registry ordering lives on the repository as a shared constant:

```java
Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");
```

Both `GET /api/servers` and `HealthCheckService.checkAll()` load through it.
§4.4 requires the check results to arrive in list order; two independently
declared sorts would satisfy that on the day they were written and then silently
diverge the first time one of them changed.

---

## 9. Configuration

`application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/serverregistry
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  sql:
    init:
      mode: always
  jpa:
    hibernate:
      ddl-auto: validate
    # Must stay false. Setting it true defers schema.sql until after Hibernate has
    # initialised, so ddl-auto=validate runs against an empty database and startup
    # fails with "missing table [servers]".
    defer-datasource-initialization: false
    # No lazy associations exist and responses are mapped to records before serialising,
    # so a request-scoped session buys nothing. Off explicitly to silence the warning.
    open-in-view: false

healthcheck:
  timeout-ms: 3000
```

`spring.threads.virtual.enabled: true` puts Tomcat request handling on virtual
threads. That is separate from — and does not replace — the executor in
`HealthCheckService`.

Bind `healthcheck.timeout-ms` with `@ConfigurationProperties` or `@Value`.

---

## 10. Critical implementation constraints

These are the things that break silently if got wrong.

**1. `HealthCheckService.checkAll()` must not be `@Transactional`, and must not
hold an open persistence session across the socket calls.** Load the list, let
the transaction close, *then* probe. If probing happens inside a transaction,
the connection pool (default 10) caps real concurrency at 10 no matter how many
virtual threads are spawned. It fails as slowness rather than an error, so it
will not be obvious.

**2. Never resolve `hostname`.** `new InetSocketAddress(String, int)` performs a
DNS lookup when given a name. It must always receive the literal `ip_address`.

**3. Close every socket.** Use try-with-resources. Leaked descriptors under
fan-out exhaust the process limit quickly.

**4. Timeout alignment.** A 3000 ms probe timeout means the request can take ~4
seconds. Any proxy, load balancer, or frontend fetch timeout in front of the app
must comfortably exceed that, or a normal slow check returns a gateway timeout.

---

## 11. Build order

Build and verify in this sequence:

1. **Phase 1** — `schema.sql`, entity, repository, and the three CRUD endpoints
   with validation and the exception handler. Verify with curl; no probing code
   yet.
2. **Phase 2** — probe a *single* server sequentially, exercised from a test.
   Confirm timeout behaviour and error messages are what you expect. No
   concurrency yet.
3. **Phase 3** — wrap phase 2 in the virtual-thread executor and expose
   `POST /api/servers/check`.

Phase 2 before phase 3 matters: if concurrency and probe semantics are built
together, every wrong result looks like a race condition.

---

## 12. Acceptance criteria

- [ ] `POST /api/servers` without `port` saves the row with port `22`.
- [ ] Posting the same `(ipAddress, port)` twice returns `409`, not `500`.
- [ ] `ipAddress: "10.0.1.256"` returns `400` with a readable message.
- [ ] `serverType: " db "` is stored and returned as `"DB"`.
- [ ] `serverType: ""` returns `400`; any non-blank value up to 30 chars is accepted.
- [ ] `GET /api/servers/types` returns each distinct value once, sorted.
- [ ] `port: 0` and `port: 70000` both return `400`.
- [ ] `DELETE` on a non-existent id returns `404`.
- [ ] `POST /api/servers/check` with an empty registry returns `200` and `[]`.
- [ ] A registry where every server is unreachable returns `200`, not `5xx`.
- [ ] Checking N servers that all time out takes ≈ the timeout, not N × timeout.
      Verify with 5+ unreachable entries; this proves the fan-out works.
- [ ] A reachable server reports a non-null `latencyMs` and null `error`.
- [ ] An unreachable server gives up after the **configured** `healthcheck.timeout-ms`.
      A hardcoded value that happens to match the configured one satisfies every
      other item on this list, so this has to be checked against a value the test
      overrides — see the timeout notes below.

### Suggested tests

- `HealthCheckService` against a real `ServerSocket` bound to an ephemeral port
  (`new ServerSocket(0)`) for the reachable case, and a closed port or
  non-routable address for the unreachable case.

  Pick the unreachable address carefully. Some environments transparently proxy
  outbound connections and will report *any* address as reachable, which turns
  the timeout tests green for the wrong reason. Verify the chosen address
  actually times out before trusting those tests. TEST-NET-3 (`203.0.113.0/24`,
  RFC 5737) on an unusual port is a safer choice than `10.255.255.1`, and ports
  80, 443 and 53 are the ones most likely to be intercepted. Measured on the
  development machine: `203.0.113.5:9999` times out as intended, while the same
  address on 80 and 443 **connects in under 50 ms**. Timing assertions must use a
  black-holed address — a *refused* port returns instantly and proves nothing
  about the timeout.
- `@WebMvcTest` on `ServerController` for validation and status codes. The slice
  does not scan `@Service`, so `HealthCheckService` needs a `@MockBean` or the
  controller cannot be constructed and every test in the class fails at once.
- The concurrency assertion from the acceptance list: total elapsed time for N
  timing-out servers must be well under N × timeout.

### Required: one integration test against a real database

The two suggestions above mock `ServerRepository`, so several things are never
executed at all and would fail in production while the suite stayed green:

| Never exercised by mocks | How it fails |
|---|---|
| Entity fields vs. the real columns | Startup error, suite green |
| `uq_servers_ip_port` | `500` instead of `409` |
| `ck_servers_port` | Bad ports reach the table |
| The `@Query` JPQL (§4.5) | Parsed only when JPA starts — never in a slice test |
| `created_at` written by the DB default | Null timestamps in responses |

So one `@SpringBootTest` class with `@Testcontainers`, a
`PostgreSQLContainer<>("postgres:15")` — the production major version — and
`@ServiceConnection` to wire it up. Cover the table above and the endpoint paths
that depend on real rows; do **not** re-test validation or status codes there.
The slice tests already cover those in milliseconds, and duplicating them
against a container buys nothing but a slower build.

If Docker is unavailable the class must **fail, not skip**. A test that silently
stops running recreates exactly the blind spot it was written to remove.

### Required: the timeout must come from configuration

Two probe tests belong in that same class, not because they need the database but
because they need the *wired* application. `HealthCheckServiceTest` constructs the
service with `new HealthCheckService(repository, 300)`, so it never sees what
Spring binds:

| Bug | Caught by the hand-built tests? |
|---|---|
| `probe()` hardcodes a timeout, ignoring the field | Yes — its own value is ignored too |
| The value Spring binds never reaches the socket | No — Spring is bypassed |
| Fan-out serialises once a real connection pool exists | No — there is no pool |
| `application.yml` key renamed, `@Value` falls back to a default | No |

So: override `healthcheck.timeout-ms` on the test class to a value well below the
3000 ms in `application.yml`, register a black-holed server, and assert the
request's elapsed time tracks the override. A hardcoded 3000 then breaks the
upper bound. The override also keeps the other probe tests in the class
sub-second instead of costing a full production timeout each.

**The override creates one blind spot, so close it.** `properties = ` contributes
its own highest-precedence property source, which means the key resolves whether
or not `application.yml` still defines it. Rename the key there and give the
`@Value` a `:3000` fallback and the whole suite stays green while production
silently ignores its configuration. Add one assertion that reads
`healthcheck.timeout-ms` from the shipped property source itself, via
`ConfigurableEnvironment`. Check the **name** only — the number is an operational
choice that may be tuned, whereas the name is a contract between
`application.yml` and the `@Value` that resolves it.

**Size the concurrency test above the connection pool.** The constraint in §10.1
is that a held session would cap real concurrency at the pool size, which is
Hikari's default 10. Eight mocked servers cannot demonstrate that — there is no
pool. Use `pool + 2` unreachable servers against the container so the cap would
actually bind if it existed.

**Docker API version.** docker-java negotiates API 1.32 by default. Docker
Engine 29.x declares `MinAPIVersion 1.40` and answers `/v1.32/info` with an
empty HTTP 400, which Testcontainers reports as the badly misleading "Could not
find a valid Docker environment" — the socket is fine and `docker ps` works.
Surefire therefore sets `api.version=1.41`. It must be a **system property**;
the `DOCKER_API_VERSION` environment variable is not read.

---

## 13. Non-goals

Do not build these. They were considered and deliberately excluded:

- Check history / results table, and any endpoint that reads past results
- Scheduled or background checks
- Authentication, authorisation, rate limiting
- ICMP ping, HTTP status checks, or protocol-specific handshakes — **TCP connect
  only**
- Update / `PUT` endpoint
- Soft delete
- Pagination, filtering, sorting parameters
- Async job mode, polling, SSE, or WebSockets
- CI configuration, or a frontend.
- ~~A `Dockerfile` or compose file for the application.~~ **Added — see §14.**
  Packaging was originally excluded here; it is now in scope because the API has
  to run on a production server. Nothing about §§1–12 changed to accommodate it:
  no endpoint, no dependency and no schema was added, and the deployment reads
  the same `application.yml` the development run does.

Each of these can be added later without changing what is specified here.

---

## 14. Deployment

Docker Compose is the production path; systemd running the jar directly is
supported as an alternative. `DEPLOY.md` is the operational guide — this
section records only the decisions, so they are not re-litigated later.

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage: Maven + JDK 21 builds, `21-jre` runs, unprivileged user |
| `compose.yaml` | App plus `postgres:15`, private network, named volume |
| `compose.prod.yaml` | App only, against a PostgreSQL already running on the host |
| `.env.example` | Template for all three paths — also installed as `/etc/server-registry.env`; `.env` is gitignored |
| `deploy/server-registry.service` | systemd unit for the jar-on-a-VM path |

**No configuration is compiled in.** Every operational value in
`application.yml` is `${ENV_VAR:development-default}`. The defaults are what
makes a local `mvn spring-boot:run` work with no setup; deployment overrides them
and never edits the file.

**Credentials keep their development defaults in `application.yml`.** Dropping
the default to force an override was considered and rejected: an unresolvable
placeholder fails at context startup, which breaks the local run and the §12
integration test along with it. Enforcement sits one layer out, in the
deployment, which knows it is production — `compose.yaml` uses `${DB_PASSWORD:?}`
and will not start without it. The systemd path cannot fail this way and says so
in its own template.

**Tests do not run during the image build.** The §12 integration test starts a
PostgreSQL container, and there is no daemon inside a build. `mvn test` is a
precondition of deploying, not a stage of it.

**The compose port binds to `127.0.0.1` by default.** With no authentication
(§13) and a `POST /api/servers/check` that connects to any registered address, a
publicly bound port is an unauthenticated port scanner pointed at the internal
network. Widening `APP_BIND` is a decision that requires something else to be
authenticating the caller first.

**Egress is deliberately unrestricted.** The systemd unit is otherwise hardened
(`ProtectSystem=strict`, `NoNewPrivileges`, read-only filesystem — the app writes
nothing to disk) but sets no `IPAddressDeny`, because reaching arbitrary
registered addresses is the product. Which targets are legitimate is a network
question, so it is answered with firewall rules rather than in the unit file.

**Both containers are capped, and the cap is what sizes the heap.** One core and
768 MB for the app, one core and 512 MB for the database, all four overridable
from `.env`; the systemd unit sets the same figures as `MemoryMax` and
`CPUQuota`. The app is given `-XX:MaxRAMPercentage=75` rather than a fixed
`-Xmx` so that the two cannot drift apart — a limit raised in one place raises
the heap with it, and there is no second number to forget. A CPU cap is close to
free here: `POST /api/servers/check` spawns a virtual thread per registered
server, but they spend the probe blocked on a socket, so capping cores makes a
large fan-out slower to start and not slower to finish.

The IDE will underline `memory: ${APP_MEMORY_LIMIT:-768m}`. The Compose schema
requires that field to match `^[0-9]+(b|k|m|g|kb|mb|gb)?$` and does not account
for interpolation; Docker substitutes before it validates, and `docker compose
config` passes. Hardcoding the value to silence the warning would undo the rule
at the top of this section.

**Single instance only.** `spring.sql.init.mode=always` plus `CREATE TABLE IF NOT
EXISTS` is idempotent but not concurrency-safe. A second app container against
the same database needs the DDL moved to Flyway and `mode=never` first — which is
the moment the §1 "no Flyway" rule should be revisited, and not before.
