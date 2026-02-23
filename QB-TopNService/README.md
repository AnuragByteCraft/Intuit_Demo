# QB Commerce Analytics — Master Guide

**One sentence:** Merchant platforms send sales to a webhook; the app stores them, builds daily aggregates and weekly Top-N by category, and can run a forecast pipeline—all with zero external infrastructure (in-memory for demo).

**One paragraph:** Multi-tenant commerce analytics for QuickBooks Commerce. We ingest sales from merchant platforms, store in a ledger (simulated Postgres), build daily/weekly aggregates in a serving store (simulated Cassandra), precompute Top-N with hot cache (simulated Redis). Forecast pipeline uses **Moving Average** when history is limited and **Prophet** (local Python script) when there is enough data; results are written to Cassandra and cache and served via forecast API.

---

## Table of contents

1. [Who this is for & prerequisites](#1-who-this-is-for--prerequisites)
2. [Problem we're solving](#2-problem-were-solving)
3. [What the app does](#3-what-the-app-does)
4. [High-level flow](#4-high-level-flow)
5. [Component reference](#5-component-reference)
6. [Simulated infrastructure vs business logic](#6-simulated-infrastructure-vs-business-logic)
7. [How to run](#7-how-to-run)
8. [How to test](#8-how-to-test)
9. [Logs & configuration](#9-logs--configuration)
10. [API reference](#10-api-reference)
11. [Repository layout](#11-repository-layout)
12. [Flow diagrams](#12-flow-diagrams)
13. [Design notes](#13-design-notes)
14. [Quick reference](#14-quick-reference)
15. [PPT pseudo code (slides & walkthroughs)](#15-ppt-pseudo-code)
16. [Observability and monitoring](#16-observability-and-monitoring)
17. [Forecast models](#17-forecast-models)

---

## 1. Who this is for & prerequisites

**Audience:** Anyone who wants to run and test the app. Use a terminal (Mac/Linux or Windows Command Prompt / PowerShell).

**Prerequisites:** Java 17+, Maven 3.8+. Check: `java -version` and `mvn -v`.

---

## 2. Problem we're solving

We model the full flow: ingest → store → aggregate → Top-N → forecast, with **in-memory substitutes** for Kafka, Postgres, Cassandra, Redis, and S3. Same logic and flow, zero infrastructure, runnable on your laptop.

---

## 3. What the app does

- **Webhook ingestion** — Accepts sales transactions (e.g. from Shopify/POS).
- **Event streaming** — Publishes to in-memory queue (simulated Kafka).
- **Ledger write** — Background consumer writes each event to ledger (simulated Postgres) with idempotency.
- **Aggregation** — Scheduled job builds daily aggregates and materializes Top-N (DAILY, WEEKLY, MONTHLY, YEARLY) into serving store and cache.
- **Top-N read** — QueryService serves Top-N: cache first, then Cassandra or compute. Supports materialized (DAILY/WEEKLY/MONTHLY/YEARLY) or custom date range (start/end, max 90 days).
- **Reconciliation** — Scheduled job syncs/checks ledger vs aggregate store.
- **Forecast pipeline** — On-demand (`POST /api/v1/forecast/run`) or scheduled. Hybrid: Moving Average when history &lt; minHistoryDaysForProphet, else Prophet (local Python script). ForecastWriteService writes to Cassandra and cache.
- **Forecast read** — ForecastService serves results (cache first, then Cassandra); no model on read.

---

## 4. High-level flow

**Path A — Write path:** Client → Webhook → Idempotency → Publish to event bus → Background thread consumes → Writes to ledger. Response 202 as soon as event is published.

**Path B — Aggregation (scheduled):** Every 15s: ledger snapshot → Phase 1: aggSeen dedupe, in-memory deltas, batch merge to Cassandra; Phase 2: per affected (tenant, merchant) compute Top-N for DAILY/WEEKLY/MONTHLY/YEARLY, store in Cassandra and Redis. Every 60s: Reconciliation.

**Path C — Read paths:** Top-N: QueryService → cache → Cassandra or compute. Forecast: cache → Cassandra; no model on read.

---

## 5. Component reference

| Component | What it does |
|-----------|--------------|
| **WebhookController** | POST transactions; validates, delegates to IngestionService, returns 202 + requestId. |
| **AnalyticsController** | GET Top-N; delegates to QueryService. |
| **ForecastController** | POST forecast/run (pipeline), GET forecast (read). |
| **GlobalExceptionHandler** | IllegalArgumentException → 400 with `{"error": "message"}. |
| **IngestionService** | Atomic idempotency (Redis putIfAbsent); publishes to event bus. |
| **TransactionWriterService** | Background thread: poll bus → PostgresLedgerRepository.upsertIdempotent. |
| **PostgresLedgerRepository** | Idempotent upsert by (tenantId, transactionId); snapshot() returns shallow copy. |
| **AggregationService** | Scheduled: snapshot → aggSeen → in-memory deltas → batchMerge; then materialize Top-N for DAILY/WEEKLY/MONTHLY/YEARLY. getMaterializedTopN / getTopNForCustomRange. |
| **QueryService** | Read path for Top-N: materialized vs custom range → AggregationService. |
| **ReconciliationService** | Scheduled sync/check ledger vs Cassandra; logs for demo. |
| **ForecastPipelineRunner** | Select targets → features → Moving Average or Prophet → ForecastWriteService.write. |
| **FeatureExtractionService** | Build features (avgSales, last7Avg, volatility); getPointsCount, getHistoryJson for Prophet. |
| **MovingAverageForecastService** | Baseline: train, quality gate (points ≥ 7), champion, predict. |
| **ProphetForecastService** | History JSON → ProphetScriptRunner → forecast points. |
| **ProphetScriptRunner** | Runs local Python script; stdin request JSON, stdout points JSON. |
| **ForecastWriteService** | Writes forecast points to Cassandra and Redis (shared for baseline and Prophet). |
| **ForecastService** | Serves forecast: cache first, then Cassandra. |
| **ForecastPipelineScheduler** | Optional: when scheduleEnabled, runs pipeline for all tenants on delay. |
| **CassandraServingRepository** | Daily aggregates, materialized Top-N, forecasts; listMerchants, listTenantIds. |
| **RedisCacheRepository** | get/put with TTL; putIfAbsent for idempotency and aggSeen. |
| **S3FeatureStoreRepository** | Feature store (putFeatures / getFeatures). |
| **InMemoryEventBus** | publish / poll (BlockingQueue). |
| **TenantHeaderFilter** | X-Tenant-Id → TenantContext; clears in finally. |

---

## 6. Simulated infrastructure vs business logic

**Simulated** = in-memory replacements for Kafka, Postgres, Cassandra, Redis, S3. **Business logic** (idempotency, aggregation, Top-N, forecast pipeline, etc.) is real.

| Real | Demo |
|------|------|
| Kafka | InMemoryEventBus |
| PostgreSQL | PostgresLedgerRepository (Map) |
| Cassandra | CassandraServingRepository (Maps) |
| Redis | RedisCacheRepository (Map + TTL) |
| S3 | S3FeatureStoreRepository (Map) |

**Concurrency:** Ingestion uses Redis putIfAbsent(idemKey) so only one request per transactionId publishes. Aggregation uses putIfAbsent(aggSeen:tenant:txnId) so each transaction is aggregated at most once.

---

## 7. How to run

**Option 1 — IDE:** Open project as Maven project, run `com.qb.analytics.MainApplication`. Wait for "Started MainApplication".

**Option 2 — Terminal:** `cd QB-TopNService` then `mvn spring-boot:run` or `mvn -q -DskipTests package` and `java -jar target/qb-commerce-analytics-0.0.1-SNAPSHOT.jar`. Base URL: `http://localhost:8080`. Health: `curl http://localhost:8080/actuator/health`.

---

## 8. How to test

(Use a second terminal; app on 8080.)

1. **Send transactions:**  
   `curl -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: tenantA" -d '{"transactionId":"t1","merchantId":"m1","categoryId":"electronics","amount":1500.0,"eventTime":"2026-02-17T00:00:00Z"}'`  
   (Repeat with t2, t3; change transactionId and amounts if desired.)
2. **Wait ~15s** for aggregation.
3. **Get Top-N:** `curl "http://localhost:8080/api/v1/analytics/topn?merchantId=m1&timeframe=WEEKLY&n=5" -H "X-Tenant-Id: tenantA"`
4. **Run forecast:** `curl -X POST "http://localhost:8080/api/v1/forecast/run" -H "X-Tenant-Id: tenantA"`
5. **Get forecast:** `curl "http://localhost:8080/api/v1/forecast?merchantId=m1&categoryId=electronics&horizonDays=7" -H "X-Tenant-Id: tenantA"`

**Unit tests:** `mvn test` (JUnit 5, in-memory repos under `src/test/java`).

---

## 9. Logs & configuration

**Logs:** Startup (MainApplication started, writer thread); idle (TransactionWriter listening); after ingest (published / idempotency hit); after Top-N (source=cache|cassandra|computed); after forecast run (pipeline, MovingAverage or Prophet, ForecastWriteService); forecast read (cache hit/miss). Set `logging.level.com.qb.analytics: DEBUG` for more.

**Configuration** (`application.yml`, bound to ForecastConfig for `demo.forecast.*`):

| Key | Meaning | Default |
|-----|---------|---------|
| demo.schedules.aggregationDelayMs | Delay between aggregation runs (ms) | 15000 |
| demo.schedules.reconciliationDelayMs | Reconciliation delay (ms) | 60000 |
| demo.topn.defaultN / maxN | Top-N size | 10 / 50 |
| demo.schedules.forecastDelayMs | Scheduled forecast delay (ms) | 3600000 |
| demo.forecast.historyDays | Days of history | 90 |
| demo.forecast.horizonDaysDefault | Default horizon | 7 |
| demo.forecast.scheduleEnabled | Run pipeline on schedule for all tenants | false |
| demo.forecast.minHistoryDaysForProphet | Use Prophet when points ≥ this | 28 |
| demo.forecast.prophetPythonPath | Python executable | .venv/bin/python3 |
| demo.forecast.prophetScriptPath | Prophet script | scripts/prophet_predict.py |

Prophet: `python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt`.

---

## 10. API reference

**All requests:** Header `X-Tenant-Id: <tenantId>` (e.g. tenantA). Tenant can default to defaultTenant.

- **POST /api/v1/webhooks/transactions** — Body: `transactionId`, `merchantId`, `categoryId`, `amount`, `eventTime`. Response 202: `{"status":"ACCEPTED","requestId":"..."}`. Idempotency by tenantId + transactionId.
- **GET /api/v1/analytics/topn** — Params: `merchantId` (required), `timeframe` (DAILY|WEEKLY|MONTHLY|YEARLY or CUSTOM with start&end), `n`, `metric` (REVENUE|UNITS). CUSTOM requires both start and end (yyyy-MM-dd), range ≤ 90 days; invalid → 400. Response includes `bucketStart` for materialized.
- **POST /api/v1/forecast/run** — Trigger pipeline (one tenant). No body.
- **GET /api/v1/forecast** — Params: `merchantId`, `categoryId`, `horizonDays`. Response: `modelUsed` (MOVING_AVERAGE_BASELINE or PROPHET), `points` (date, predictedSales, confidenceLow, confidenceHigh).
- **GET /actuator/health** — Health check.

---

## 11. Repository layout

```
QB-TopNService/
├── src/main/java/com/qb/analytics/
│   ├── MainApplication.java
│   ├── config/ (ForecastConfig, WebConfig)
│   ├── controller/ (Webhook, Analytics, Forecast)
│   ├── exceptionhandler/ (GlobalExceptionHandler)
│   ├── service/ (Ingestion, TransactionWriter, Aggregation, Query, Reconciliation)
│   ├── service/forecast/ (ForecastPipelineRunner, Scheduler, ForecastService, FeatureExtraction, MovingAverage, Prophet, ProphetScriptRunner, ForecastWriteService)
│   ├── repository/ (PostgresLedger, CassandraServing, RedisCache, S3FeatureStore)
│   ├── infra/ (InMemoryEventBus, TenantContext, TenantHeaderFilter)
│   └── model/ (TransactionEvent, AggregateRecord, TopNResponse, ForecastPoint, ForecastResponse)
├── src/test/java/ (same package; tests for config, repos, services, forecast)
├── src/main/resources/application.yml
├── scripts/ (prophet_predict.py, requirements.txt)
└── README.md (this file)
```

---

## 12. Flow diagrams

**Ingestion:** Client → TenantHeaderFilter → WebhookController → IngestionService (putIfAbsent → publish) → 202. TransactionWriterService polls bus → PostgresLedgerRepository.upsertIdempotent.

**Top-N read:** Client → AnalyticsController → QueryService (custom range or materialized) → AggregationService (cache → Cassandra → compute) → JSON.

**Forecast pipeline:** Runner → targets → FeatureExtraction → if points &lt; threshold → MovingAverageForecastService → ForecastWriteService; else ProphetForecastService (ProphetScriptRunner) → ForecastWriteService.

**Forecast read:** Client → ForecastController → ForecastService (cache → Cassandra) → JSON.

---

## 13. Design notes

Single responsibility per service; controller → service → repository. ForecastPipelineRunner orchestrates only; MovingAverageForecastService holds baseline champion; Prophet in ProphetForecastService + ProphetScriptRunner. QueryService chooses materialized vs custom range. Repository pattern; scheduled jobs for aggregation and reconciliation; producer/consumer with InMemoryEventBus.

---

## 14. Quick reference

| Method | Path | Purpose |
|--------|------|---------|
| POST | /api/v1/webhooks/transactions | Ingest transaction |
| GET | /api/v1/analytics/topn?merchantId=...&timeframe=WEEKLY&n=10 | Top-N |
| POST | /api/v1/forecast/run | Trigger forecast pipeline |
| GET | /api/v1/forecast?merchantId=...&categoryId=...&horizonDays=7 | Get forecast |
| GET | /actuator/health | Health |

Header: `X-Tenant-Id: tenantA`.

---

## 15. PPT pseudo code

**Slide 1 — Event ingestion:** putIfAbsent(idemKey, requestId) → publish once; duplicate returns same requestId (202). Key: idem:webhook:tenantId:transactionId.

**Slide 2 — Ledger writer:** Poll bus → upsertIdempotent(tenantId, transactionId). Duplicate writes no-op.

**Slide 3 — Aggregation:** Snapshot → aggSeen dedupe → in-memory deltas → batchMergeDailyAggregates. Then per affected (tenant, merchant): bucketRange(DAILY|WEEKLY|MONTHLY|YEARLY), compute Top-N, save with bucket in key, cache.

**Slide 4 — Query path:** Custom range: validate ≤90 days → cache or compute. Materialized: resolve bucket → cache → Cassandra → compute. Key includes bucketStart.

**Slide 5 — Forecast:** Targets → features (sorted by day) → train/quality/champion (baseline) or Prophet script → ForecastWriteService (Cassandra + Redis).

**Slide 6 — Multi-tenant:** Tenant from X-Tenant-Id; keys = tenantId:…. Reconciliation: compare ledger vs aggregates → recompute on gaps.

---

## 16. Observability and monitoring

**Pillars:** Metrics (Prometheus), Logs (ELK: Elasticsearch, Logstash, Kibana), Tracing (Jaeger), Dashboards (Grafana), Alerting (PagerDuty or similar).

**Metrics to capture:** Ingestion (request count, latency p50/p95/p99, idempotency hits, errors); queue depth / ledger write count and latency; aggregation run duration, snapshot size, Top-N materialization count; Top-N and forecast request count and latency, cache hit rate; forecast pipeline duration and quality gate pass/fail; error rate; data freshness; JVM health.

**Alerts (examples):** High error rate (&gt;1% for 2 min); high ingestion or Top-N latency (p99 &gt; threshold); consumer lag growing; aggregation failures; data freshness (no successful run in N min); forecast pipeline failing.

**Where to look:** Grafana (metrics), Kibana (logs), Jaeger (traces), PagerDuty (alerts). AI can help with alert triage, anomaly detection, root cause, log analysis, runbooks, and incident summaries—introduce gradually with humans in the loop.

---

## 17. Forecast models

**What we do:** Predict future sales per merchant/category from daily aggregates; store and serve from cache/DB.

**Models:** **Moving Average (baseline)** for short history (points &lt; minHistoryDaysForProphet): avgSales per day, band = avg ± volatility; quality gate ≥ 7 points. **Prophet** (local Python script) when points ≥ threshold: trend and seasonality; script reads JSON from stdin, writes points JSON to stdout. Both write via ForecastWriteService.

**Hybrid rule:** One threshold (e.g. 28 days). Below → MovingAverageForecastService; at or above → ProphetForecastService. No Docker; ProcessBuilder; JSON in/out.

**Adding another model:** Same contract (List&lt;ForecastPoint&gt;) → new path in runner or new service + ForecastWriteService; or pluggable ForecastModel interface; or external HTTP/gRPC service client. Pipeline (target selection, feature extraction, write) stays shared.

**Fine-tuning (later):** More history/features, outlier handling; Prophet parameters; quality gate on fit; backtesting; champion persistence; retries and fallback; monitoring model type and accuracy.

---

*This document is the single master reference for QB-TopNService.*
