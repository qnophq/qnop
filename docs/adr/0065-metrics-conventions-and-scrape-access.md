# ADR-0065: Metrics — conventions, cost of a scrape, and who may read it

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** devtank42 (with Claude)

## Context

ADR-0037 put `/actuator/prometheus` in place with three gauge binders and deferred the rest. Issue
#962 builds that rest out: timers and counters across the domain, a Grafana dashboard, alert rules.
Before the first of many meters is added, three things in the existing setup had to be settled,
because each gets worse with every meter that follows.

**Nobody could run an unattended scrape.** The scrape required `ROLE_ADMIN`, and the only
credential that carries it is a JWT access token: short-lived, rotating, issued to a person.
Prometheus needs something it can hold in a config file for months.

**A scrape cost a database query per gauge.** Gauges are evaluated one by one at scrape time. The
four `qnop.jobs` gauges each called `JobService.queueStats()`, which runs four counts: sixteen
queries per scrape, for four numbers. Every further database-derived gauge would have added its
own.

**There were no rules for names and tags.** A tag that carries a user id or a document id multiplies
the number of time series by the number of users or documents, and puts a personal identifier into
a system with no access control of the kind the product enforces. ADR-0054 settled that question
for logs; nothing settled it for metrics.

A fourth point is not a defect but a trap: Spring Boot publishes far more than is useful. A first
scrape of an idle instance had 587 series, 92 of them timing every Spring Security filter.

## Decision

### Names and tags

- Domain meters are named `qnop.<area>.<thing>`, lower-case and dotted, in base units (seconds,
  bytes). Framework meters keep their standard names.
- **A tag value comes from a closed set**: an enum or a fixed catalogue such as the job type, the
  scheduler job id, the mail template key, a storage operation, a format, an outcome. Never a user,
  document or team id, an e-mail address, a slug, a file name, an IP address, or the name an admin
  gave an OIDC provider. This is the metrics counterpart of ADR-0054's rule for logs and holds for
  the same reason, plus one more: unbounded tags are unbounded cost.
- `outcome` is always a small closed set.
- Every series carries `application="qnop"`. `qnop.info{version, edition}` is the build as an info
  metric (value 1, the facts in the tags).
- Latency that matters is published as **histogram buckets with a fixed list of boundaries**, never
  as client-side percentiles (they cannot be aggregated across instances) and not through
  `percentiles-histogram` (about sixty buckets per route, method and status).

### What a scrape may cost

Gauges derived from the database read a **snapshot that is loaded once and shared**, at most
fifteen seconds old (`CachedSnapshot`). A scrape therefore issues a bounded number of statements
however many gauges exist. A failed load is remembered for the same fifteen seconds and every
gauge reports `NaN`: without that, each gauge would retry on its own and, with the database down,
wait out the connection timeout in turn, holding a request thread for minutes per scrape. The health indicator keeps reading live: a probe wants the truth now, not fifteen
seconds ago.

Such gauges are **the same on every instance**, because they describe the database, not the pod.
Dashboards and alerts aggregate them with `max`, and per-instance counters and timers with `sum`.
The metric catalogue (`docs/OBSERVABILITY.md`, which arrives with the dashboard in a later part of
issue #962) states which is which for every meter.

### Framework meters that are switched off

| Meter | Why |
|---|---|
| `spring.security.*` observations | A timer per filter and per authorization decision; framework internals. Login outcomes get their own `qnop.auth.*` meters. |
| `spring.data.repository.invocations` | A timer per repository method: hundreds of series that say less than the request timer and the pool meters. |
| `http.server.requests.active` | In-flight requests per route; Tomcat's busy-thread gauge answers it with one series. |

Tomcat's MBean registry is switched on, because without it the thread-pool meters do not exist and
a saturated request pool is invisible.

### Who may read the scrape

Unchanged by default: `ROLE_ADMIN`. Two opt-in ways for a machine, both off unless configured:

- **A management port** (`QNOP_MANAGEMENT_PORT`). Actuator moves to its own port; a scrape arriving
  there needs no credential. The port must not be published through the ingress: reaching it is the
  access control. This is what a Kubernetes `ServiceMonitor` or a Prometheus on the same compose
  network expects, and it keeps probe and scrape traffic off the public listener.
- **A scrape token** (`QNOP_METRICS_SCRAPE_TOKEN`, at least 32 characters and no whitespace, or
  the instance refuses to start). A static bearer token for deployments that can only reach the
  application port. The start-up error names the rule and never repeats the value.

Both are implemented as one request matcher, `MetricsScrapeAccess`, on a security chain of its own
ahead of the API chain. It matches `GET /actuator/prometheus` exactly. Three properties make it
safe to reason about:

- **It grants no authentication.** The chain permits the request and nothing more, so no role-based
  rule anywhere opens with it; health details stay hidden from the token.
- **It fails closed.** Anything the matcher does not claim falls through to the existing chain,
  where the scrape needs `ROLE_ADMIN`. A wrong token is rejected there as an invalid bearer token.
  The right token sent to the wrong place (a `HEAD`, a trailing slash, a mistyped metrics path)
  is rejected the same way, and before the machine-credential authenticators of ADR-0060 are
  consulted: add-on code never sees it.
- **The port is the socket's.** The management port is compared with the local port the connection
  arrived on, never with the `Host` header or a forwarded port, which the caller chooses. The token
  is compared as SHA-256 digests in constant time.

## Consequences

- An operator can point a stock Prometheus at qnop without minting admin tokens.
- Adding a database-derived gauge no longer adds a query per scrape, but it does mean such a gauge
  can be up to fifteen seconds stale. For alerting on queue depth that is irrelevant.
- The tag rule rules out per-team and per-user dashboards by design. Questions of that kind belong
  to the audit trail and the product's own views, which have access control.
- A scrape token is a secret with no expiry. It is redacted on the admin configuration page (its
  name ends in `token`) and opens exactly one read-only endpoint, but rotating it means a restart.
- The switched-off framework meters are a judgement about signal against cost; each is one line in
  `application.yml` to bring back.
- This ADR replaces the "Access" paragraph and the "Deferred" note of ADR-0037; the rest of that ADR
  stands.

## Alternatives considered

- **Leave the scrape admin-only and document a token-refresh sidecar.** Rejected: it makes every
  operator build the same workaround and puts an admin credential, with everything it can do, into
  the monitoring stack.
- **Make the scrape anonymous.** Rejected: queue depth, error rates and the running version are an
  attacker's reconnaissance.
- **Authenticate the scraper through the machine-credential SPI (ADR-0060).** Rejected for now:
  that SPI has no Community implementation, and a scrape needs no identity, only admission.
- **One count query instead of a cached snapshot.** A single grouped query would also remove the
  sixteen, but not the principle: the next gauge would bring its own query. Caching bounds the cost
  for all of them.
- **`percentiles-histogram: true`.** Rejected on cardinality; see above.
