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
  An inventory gauge, which counts what exists, is named for the thing alone: `qnop.jobs`,
  `qnop.reviews`, `qnop.users`.
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

### Where meters are recorded

`qnop-core` depends on `micrometer-core`, the Spring-free facade; which registry backs it stays the
application's choice. This is the seam ADR-0037 deferred. The meters live in
`io.qnop.observability.metrics`, one small class per area that owns its meter names and takes its
tag values as enums, so for those the closed-set rule is enforced by the type system rather than
by review. Three tags are strings because their sets live elsewhere: the job `type` (the type of a
registered handler), the scheduler `job` (an id from the scheduler catalogue) and the rate-limit
`scope` (the name a limit declares in code, never the key it limits). The first two are bounded by
their call sites, which return before metering when no handler or no work is registered; a new
caller must keep that. An enum that is open by design is closed before it becomes a tag: a workflow
state an add-on introduces (ADR-0011) is counted under `other`.

Services record at their edges (a job attempt, a conversion, a scheduler run), not inside the
anchoring or diff algorithms, which stay free of both the database and metrics. `qnop-spi` and
`qnop-api` gain no dependency: an enterprise `StorageProvider` or extractor is measured by the core
code that calls it. For the object store that is literal: the storage services wrap the provider
they are given in a measuring decorator. The bean itself is not replaced, because a provider can
be more to Spring than a `StorageProvider` (a lifecycle, a listener, a health indicator), and a
wrapper in its place would hide that.

Review events are counted on the thread that committed them, not on the executor that hands them
to listeners: that executor drops when a listener is slow, and it counts what it drops
(`qnop.review.events.dropped`).

What is deliberately not measured: the HTTP calls of the OIDC login flow. They run through the
SSRF-guarded transport (issue #904), inside Spring Security's own clients; timing them
would mean wrapping that transport, and the login outcome counter already says whether sign-in
through a provider works. A login that fails before qnop's success handler runs (a forged `state`,
an error returned by the provider) is likewise not counted.

A counter that describes committed work is incremented after the commit (`AfterCommit`).
Re-anchoring results, accepted uploads, reaped jobs and reaped storage orphans are counted that way, because the queue
retries a rolled-back attempt and a request that loses a uniqueness race rolls back, and either
would otherwise be counted without having happened. Timers measure attempts, whatever their fate.

Timers and counters are per instance; a job that ShedLock runs on one pod is timed on that pod
only. Facts that must be the same everywhere, such as when a scheduled job last ran, are published
as gauges from the database instead (see below).

### What a scrape may cost

Gauges derived from the database read a **snapshot that is loaded once and shared**, at most
fifteen seconds old (`CachedSnapshot`); the product counts (reviews, annotations, accounts, stored
objects), which move slowly, at most a minute old. Two of those four reads scan their table; a
reading is abandoned after five seconds and then counts as failed. A scrape therefore issues a bounded number of statements
however many gauges exist. A failed load is remembered for as long as a successful one would be (fifteen seconds, or the
minute of the product counts) and every gauge reports `NaN`: without that, each gauge would retry on its own and, with the database down,
wait out the connection timeout in turn, holding a request thread for minutes per scrape. The health indicator keeps reading live: a probe wants the truth now, not fifteen
seconds ago.

Such gauges are **the same on every instance**, because they describe the database, not the pod.
Dashboards and alerts aggregate them with `max`, and per-instance counters and timers with `sum`.
The metric catalogue (`docs/OBSERVABILITY.md`) states which is which for every meter.

### The dashboard and the rules are held to the code

`deploy/observability/` ships a Grafana dashboard, Prometheus alert rules and a compose overlay.
A panel or a rule on a series that does not exist shows "no data", which reads as "nothing is
wrong", so they are not left to drift: a test (`MetricCatalogueTest`) registers every meter qnop
publishes, renders them as a scrape would, and fails when the dashboard or a rule names a `qnop_*`
series, a label or a label value that is not among them, when a meter has no panel, when the
catalogue and the code disagree, or when a meter carries a tag key that is not on the list of
allowed ones. (That the values behind a string tag stay a closed set rests on its call sites;
the test sees the keys.) The guarded files are declared inputs of the test task, so a change to
the dashboard alone runs it. The framework
series the dashboard uses are checked against a running application.

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

## Amendment (2026-11-19, no tag may shadow a target label)

The scheduler meters shipped with the tag `job`, as "Where meters are recorded" names it. `job` is
also the label Prometheus attaches to every series of a scrape target. With the default
`honor_labels: false` Prometheus keeps its own `job="qnop"` and renames the application's tag to
`exported_job`, so every panel and alert rule that grouped `by (job)` silently aggregated over all
maintenance jobs at once: the dashboard showed one series instead of one per job, and
`QnopScheduledJobNotRunning` could not fire for a stuck job while any other job ran (issue #979).

The tag is now `scheduler_job`. The rule it adds to "Names and tags": **a tag key never equals a
label Prometheus sets on a target itself (`job`, `instance`)**. `MetricCatalogueTest` enforces it
for every qnop meter and for the list of permitted tag keys.

## Amendment (2026-11-20, failure series exist from the start)

Counters and timers were created by their first event. A series created that way appears at its
value, and `rate()`/`increase()` need an earlier sample to rise from. That hides the first event
after a restart, and every event of a burst that falls into one scrape interval. On a local run,
seven reused refresh tokens in one burst left `increase()` at 0, and `QnopRefreshTokenReuse` did
not fire (issue #984).

The rule this adds: **a failure series that an alert rule watches, or that an error panel shows
for a dependency or a pipeline, is registered at 0 when the application starts.** The tag values are
closed sets, so the series are known up front:

- Meters whose tags are all enums register their failure series in their constructor: object-store
  errors per operation, every outcome but success a login method can end with, every refresh
  outcome but success, extraction failures per format, conversion failures, and listener failures
  per event type.
- Meters whose tag is an open-looking string still taken from a fixed set are declared by the code
  that owns the set: the job queue declares each handler's type, the mail service each catalogue
  template and the ad-hoc message, and each rate limit its scope when it is created.

Two failure series are left out on purpose. A failed scheduler run is alerted on through the
`qnop_scheduler_last_run_failed` gauge, which has no gap; registering every job × trigger × dry-run
combination would add hundreds of bucket lines for a panel. And the tracking forwards are tagged
with a backend chosen at runtime, which is not known at start.

Success series still appear with their first event. Registering them all would add series that
nobody alerts on, mail templates a deployment never sends among them. Pre-registration does not
raise the cardinality bound: every series registered at start is one the first event would have
created.

## Amendment (2026-11-21, labels outside selectors — issue #981)

The test checked the labels of selectors only. A label a panel or rule uses elsewhere fails more
quietly than a selector: `sum by (job)` over a series without `job` is one aggregate series, and
`{{ $labels.job }}` in an alert prints nothing. `MetricCatalogueTest` now also checks, for every
panel query and rule that reads a `qnop_*` series, the labels of its grouping clauses (`by`,
`without`) and vector matchings (`on`, `ignoring`, `group_left`, `group_right`), of the panel's
legend (`{{label}}`) and of the rule's annotations (`$labels.x`, `$labels["x"]`,
`index $labels "x"`). Each must be on a published sample of one of the series the expression
reads, or be one every series may carry (`application`, `instance`, `le`). The files are parsed,
not searched, so a rule's expression over several lines of YAML is read as one.

Out of scope: expressions over framework series only, which stay with the running-application
check because the test's registry does not publish them; a label that an expression mixing qnop
and framework series takes from the framework side, or that `label_replace` creates, which the
check reports and which then needs an exception in the test; series selected only through
`__name__`; templates in a rule's `labels:`; dashboard variables and Grafana transformations.
