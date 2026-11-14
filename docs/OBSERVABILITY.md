# Observability

What qnop tells you about itself, and how to look at it. The rules behind the metrics (naming, which tags are allowed, what a scrape may cost) are in [ADR-0065](adr/0065-metrics-conventions-and-scrape-access.md); how to let Prometheus in is in [DEPLOYMENT.md](DEPLOYMENT.md#metrics).

- [Quick start: Prometheus and Grafana beside qnop](#quick-start)
- [The dashboard](#the-dashboard)
- [Alert rules](#alert-rules)
- [Reading the numbers with more than one instance](#more-than-one-instance)
- [Metric catalogue](#metric-catalogue)
- [What is not measured](#what-is-not-measured)
- [For extension authors](#for-extension-authors)

## Quick start

`deploy/observability/` holds a compose overlay that adds Prometheus and Grafana to the stack of `deploy/docker-compose.yml`:

```bash
cd deploy
export GRAFANA_ADMIN_PASSWORD="$(openssl rand -base64 24)"
docker compose -f docker-compose.yml -f observability/docker-compose.observability.yml up -d
```

Grafana is then on `http://127.0.0.1:3000` (`GRAFANA_PORT` changes the port), signed in as `admin` with the password you set, with the Prometheus data source and the dashboard already in place. It listens on loopback only, because it speaks plain HTTP: put it behind your TLS proxy, or set `GRAFANA_BIND=0.0.0.0` on a network you trust.

The overlay gives qnop a management port, 8081, that is reachable inside the compose network only; Prometheus scrapes it there without a credential. Nothing but Grafana is published to the host. Prometheus keeps thirty days (`PROMETHEUS_RETENTION`).

**`/actuator/health` moves to the management port as well.** The health check of `deploy/docker-compose.yml` follows by itself; a probe of your own that asks port 8080 (a load balancer, a reverse proxy) starts failing when you apply the overlay and has to be pointed at 8081. See [DEPLOYMENT.md](DEPLOYMENT.md#metrics).

If you already run Prometheus and Grafana, take the pieces instead:

| File | Use |
|---|---|
| `deploy/observability/prometheus.yml` | the scrape job, to copy into your configuration |
| `deploy/observability/alerts.yml` | alert rules, to load as a rule file |
| `deploy/observability/grafana/dashboards/qnop-overview.json` | the dashboard, to import; pick the data source with the variable at its top |

The overlay does not include an Alertmanager: where alerts go is yours to decide. The rules are evaluated and visible in Prometheus either way.

## The dashboard

One dashboard, "qnop — Overview" (uid `qnop-overview`), 57 panels in ten rows. Read it top to bottom: the first row says whether something is wrong, the rows below say where.

| Row | Answers |
|---|---|
| Overview | Is it up, which version, how many requests fail, is the job queue moving, is the audit trail intact |
| HTTP | Request rate by outcome, latency percentiles, the slowest and the failing endpoints, Tomcat threads |
| Job queue | Depth by state, age of the oldest due job, executions, run time and wait per job type |
| Document ingest and conversion | Extractions and conversions by outcome and duration, converter slots, upload sizes, what re-anchoring did to annotations |
| Reviews | Reviews by workflow state, annotations by status, review activity, accounts |
| Sign-in and mail | Logins and refresh rotations by outcome, rate-limit refusals, mail by outcome and template |
| Object storage | Calls to the store by outcome and duration, bytes moved, stored objects and bytes, reaped orphans |
| Scheduled jobs | When each maintenance job last ran, whether it failed, whether it is enabled, how long it takes |
| Usage-tracking proxy | Forwards to the analytics backend by outcome; empty when tracking is off |
| JVM and database pool | Heap, GC pauses, CPU, database connections, threads |

The `Instance` variable narrows the per-instance panels to one or several instances. Panels built on database-derived gauges ignore it on purpose: those numbers describe the deployment, not an instance.

An empty panel is not always a fault. Most counters and timers appear with the first event: a deployment that has never sent a mail has no mail series. For the same reason a rate misses the very first event of a series after a restart: the series is born at 1, and a rate needs a 0 to rise from.

The provisioned data source tells Grafana that Prometheus scrapes every 30 seconds. If you change the scrape interval, or import the dashboard into a Grafana of your own, set the data source's scrape interval to match, or short time ranges show gaps.

## Alert rules

`deploy/observability/alerts.yml` — sixteen rules. The thresholds are starting points; tune them.

| Alert | Severity | Fires when |
|---|---|---|
| `QnopDown` | critical | an instance has not been scraped for 2 minutes |
| `QnopHighServerErrorRate` | critical | more than 5% of requests fail with a server error for 10 minutes |
| `QnopSlowRequests` | warning | the 95th-percentile response time is above 2.5 s for 10 minutes |
| `QnopJobQueueStuck` | critical | a due job has waited more than 10 minutes, for 10 minutes |
| `QnopJobsStale` | warning | jobs are marked running but have not moved, for 15 minutes |
| `QnopJobsFailing` | warning | the number of jobs that gave up after their last retry rose in the past 30 minutes |
| `QnopConverterSaturated` | warning | Word conversions are refused for want of a slot, for 15 minutes |
| `QnopScheduledJobFailed` | warning | a maintenance job's last run failed |
| `QnopScheduledJobNotRunning` | warning | an enabled maintenance job has not run for two days |
| `QnopScheduledJobNeverRan` | warning | an enabled maintenance job has no run on record, for two days |
| `QnopAuditChainBroken` | critical | the last audit-trail verification did not pass: it found a break, or could not finish |
| `QnopRefreshTokenReuse` | warning | more than five already-used refresh tokens were presented in 15 minutes |
| `QnopStorageErrors` | critical | more than 5% of object-storage calls fail for 10 minutes |
| `QnopMailFailing` | warning | mails fail to send, for 15 minutes |
| `QnopDatabasePoolExhausted` | warning | requests wait for a database connection, for 5 minutes |
| `QnopHeapNearlyFull` | warning | an instance's heap is above 90% for 15 minutes |

Three limits worth knowing. `QnopScheduledJobNotRunning` assumes a daily schedule: the hourly notification digest would be caught only after two days. `QnopMailFailing` and `QnopRefreshTokenReuse` watch counters that are created by their first event, so they fire from the second failure after a restart on; with a persistent fault that is seconds later. And `QnopAuditChainBroken` cannot tell a broken chain from a verification that failed to run through; `qnop_audit_chain_problems` and the run's message on the scheduler page can.

## More than one instance

Every series carries `application="qnop"` and Prometheus adds `instance`.

- **Counters and timers are per instance.** Each instance counts what it did. Add them up: `sum(rate(...))`. A job that the scheduler lock lets one instance run is timed on that instance only.
- **Gauges read from the database are the same on every instance**, because they describe the database. Take one: `max(...)`. Summing them multiplies the truth by the number of instances. The catalogue marks these as "database".
- **Gauges of an instance's own state** (converter slots in use, the audit-chain verification an instance last ran) are per instance, like counters.

Percentiles are computed from histogram buckets, so they can be aggregated across instances: `histogram_quantile(0.95, sum by (le) (rate(..._bucket[5m])))`.

## Metric catalogue

The names below are Micrometer's. Prometheus shows them with underscores and a unit suffix: a timer `qnop.job.execution` becomes `qnop_job_execution_seconds_count`, `_sum`, `_max` and `_bucket`; a counter gets `_total`; a gauge in bytes ends in `_bytes`.

"Scope" says how to aggregate: **instance** (sum across instances) or **database** (the same everywhere; take the max).

### Platform

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.info` | gauge | `version`, `edition` | instance | Always 1; carries the running version and edition |
| `qnop.audit.chain.intact` | gauge | – | database (take the `min`) | 1 if the last audit-chain verification passed, 0 if it found a break or could not finish; no value until one has run. Each instance reports the newer of its own run and the recorded one |
| `qnop.audit.chain.problems` | gauge | – | instance (take the `max`) | Breaks found by the last verification on this instance; no value on an instance that has not run one |

### Job queue

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.jobs` | gauge | `state` = pending, running, stale, failed | database | Queue depth. `stale`: marked running, but not moved for longer than the reaper tolerates |
| `qnop.jobs.oldest.pending.age` | gauge (s) | – | database | How long the longest-waiting due job has waited; 0 when none is due |
| `qnop.job.execution` | timer | `type`, `outcome` = success, retry, failed | instance | One attempt at a job. `retry`: failed and will be tried again; `failed`: gave up |
| `qnop.job.queue.wait` | timer | `type` | instance | Time between a job becoming due and a worker starting it |
| `qnop.jobs.reaped` | counter | – | instance | Stale jobs put back in the queue |

### Documents

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.document.extraction` | timer | `format` = pdf, docx, other · `outcome` = success, rejected, error | instance | Extracting one uploaded version. `rejected`: the upload cannot be read; `error`: the pipeline failed |
| `qnop.document.upload.size` | summary (bytes) | `format` | instance | Size of accepted uploads |
| `qnop.document.surfaces` | summary | `format` | instance | Surfaces (pages) per extracted document |
| `qnop.office.conversion` | timer | `outcome` = success, rejected, failure | instance | One Word-to-PDF conversion. `rejected`: the document is at fault; `failure`: the converter is |
| `qnop.office.conversion.wait` | timer | – | instance | Time a conversion waited for a free converter slot |
| `qnop.office.conversion.refused` | counter | – | instance | Conversions turned away because no slot came free in time; the job is retried |
| `qnop.office.conversions` | gauge | `state` = active, waiting | instance | Conversions running and queued on this instance |
| `qnop.office.conversion.limit` | gauge | – | instance | How many conversions this instance allows at once |
| `qnop.reanchor.annotations` | counter | `result` = placed, moved, orphaned | instance | What happened to annotations when a new version arrived |

### Reviews

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.reviews` | gauge | `state` = draft, in_review, changes_requested, finalized, cancelled, other | database | Reviews by workflow state. `other`: a state an add-on introduced |
| `qnop.annotations` | gauge | `status` = open, resolved, dismissed | database | Annotations by status |
| `qnop.users` | gauge | `status` = enabled, pending, disabled | database | Accounts. `pending`: self-registered, e-mail address not yet confirmed |
| `qnop.review.events` | counter | `type` = the eight review events | instance | Committed review events: annotations created and decided, comments, uploads, workflow changes |
| `qnop.review.events.listener.failures` | counter | `type` | instance | An add-on's event listener threw |
| `qnop.review.events.dropped` | counter | – | instance | Events not handed to listeners because the dispatch queue was full |

### Sign-in and mail

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.auth.login` | counter | `method` = local, oidc · `outcome` = success, rejected, throttled, error | instance | Login attempts that reached the application. `throttled`: refused by a per-account limit |
| `qnop.auth.refresh` | counter | `outcome` = success, missing, reused, unknown | instance | Refresh-token rotations. `reused`: an already-rotated token was presented again |
| `qnop.ratelimit.rejected` | counter | `scope` = the limit's name | instance | Requests a rate limit refused |
| `qnop.mail.send` | timer | `template` · `outcome` = sent, skipped, failed | instance | One attempt to send a mail. `skipped`: no SMTP server configured. `template` is the catalogue entry, or `adhoc` |

A request that a per-IP limit stops never reaches the login or refresh endpoint: it appears in `qnop.ratelimit.rejected` only. Under a flood, the sum of `qnop.auth.login` is therefore not the number of attempts. A provider login that fails before qnop takes over (a forged `state`, an error from the provider) is not counted either.

### Object storage

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.storage.operation` | timer | `operation` = put, get, exists, delete, list · `outcome` = success, error | instance | One call to the object store. An absent object is an answer, so a success |
| `qnop.storage.transferred` | counter (bytes) | `direction` = upload, download | instance | Bytes handed to the store and opened from it. A download counts the object's size when it is opened |
| `qnop.storage.orphans.reaped` | counter | – | instance | Uploads that were never committed and that the reaper removed |
| `qnop.storage.objects` | gauge | `status` = pending, committed | database | Objects in the storage registry |
| `qnop.storage.objects.size` | gauge (bytes) | `status` | database | Bytes those objects hold. Content is deduplicated, so this is less than the sum of all uploads |

### Scheduled jobs

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.scheduler.run` | timer | `job`, `outcome` = success, failure · `trigger` = scheduled, manual · `dry_run` | instance | One run of a maintenance job, on the instance that held the lock |
| `qnop.scheduler.last.run.timestamp` | gauge (s) | `job` | database | When the job last ran, as a Unix timestamp; no value if never |
| `qnop.scheduler.last.run.failed` | gauge | `job` | database | 1 if the last run failed |
| `qnop.scheduler.job.enabled` | gauge | `job` | database | 0 if an operator switched the job off |

### Usage tracking

| Meter | Type | Tags | Scope | Meaning |
|---|---|---|---|---|
| `qnop.tracking.forward` | timer | `provider` = the backend kind · `outcome` = success, rejected, error | instance | Forwarding one measurement. `rejected`: the backend answered with an error; `error`: it could not be reached |

### From the framework

Spring Boot, Micrometer and the libraries publish their own: `http_server_requests_seconds` (by `method`, `uri` template, `status`, `outcome`), `jvm_*`, `process_*`, `system_*`, `hikaricp_connections_*`, `tomcat_threads_*`, `logback_events_total`. Three families are switched off because they cost many series and answer nothing here: per-repository-method timings, the in-flight request gauge and Spring Security's filter observations (ADR-0065).

## What is not measured

- **No person, document, team or address appears in any tag.** There is no per-user or per-review series, and there will not be one: metrics are operational data with a wide audience and a long retention. For "what did this user do", the audit trail is the instrument.
- **The HTTP calls of the provider login flow** are not timed; the login outcome says whether sign-in through a provider works.
- **Database-derived gauges are snapshots**: up to 15 seconds old, the product counts up to a minute. A failed reading shows as no value rather than as a stale one.

## For extension authors

- An extension contributes meters by declaring a `MeterBinder` bean, or by injecting the `MeterRegistry`. Name them `qnop.ee.<area>.<thing>`, so they are told apart from the Community catalogue (which a test holds to exactly the list above).
- The tag rule of ADR-0065 applies to an extension as it does here: closed sets only.
- A `StorageProvider` and a `DocumentExtractor` are measured by the core code that calls them. A format an extension adds is reported under `format="other"`.
