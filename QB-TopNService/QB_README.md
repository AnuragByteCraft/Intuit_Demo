# QB Commerce Analytics Demo — Master Guide

**One sentence:** Merchant platforms send sales to a webhook; the app stores them, builds daily aggregates and weekly Top-N by category, and can run a forecast pipeline to produce predicted sales—all with zero external infrastructure (everything in-memory for demo).

**One paragraph (elevator pitch):** We are building a multi-tenant commerce analytics system for QuickBooks Commerce where merchant platforms (Shopify/POS/marketplaces) send sales transactions in near real time. The system ingests each sale reliably, stores the raw transaction in a ledger store (Postgres) for correctness/audit, and continuously builds daily/weekly aggregates in a serving store (Cassandra) to support sub-100ms dashboard reads. To serve “Top-N categories” fast, it precomputes Top-N rankings (e.g. weekly Top10/Top20) and keeps hot results in Redis cache. Separately, a batch forecasting service runs (e.g. daily), reads historical aggregates, generates future sales predictions, and stores them for dashboard consumption via a dedicated forecast API. This demo implements the same flow with in-memory substitutes for all infrastructure.

**Source of truth:** This readme is the single master reference for the QB-TopNService codebase. It is intended to be kept in sync with the code so that anyone can understand the entire system from this document alone. See [§16 Source of truth & completeness](#16-source-of-truth--completeness) and [§17 Prophet / production models](#17-production-prophet-or-other-models) at the end for what is covered and production options.

---

## Table of contents

1. [Who this is for & prerequisites](#1-who-this-is-for--prerequisites)
2. [Problem we're solving](#2-problem-were-solving)
3. [What the app does (high level)](#3-what-the-app-does-high-level)
4. [High-level flow (end-to-end)](#4-high-level-flow-end-to-end)
5. [Component reference: what each part does and how](#5-component-reference-what-each-part-does-and-how)
6. [Simulated infrastructure vs business logic](#6-simulated-infrastructure-vs-business-logic)
7. [How to run the app (step-by-step)](#7-how-to-run-the-app-step-by-step)
8. [How to test the app (step-by-step)](#8-how-to-test-the-app-step-by-step)
9. [What you'll see in the logs](#9-what-youll-see-in-the-logs)
10. [Configuration](#10-configuration)
11. [API reference](#11-api-reference)
12. [Repository layout](#12-repository-layout)
13. [Flow diagrams (ingestion, Top-N, forecast)](#13-flow-diagrams-ingestion-top-n-forecast)
14. [Design notes (SOLID, patterns)](#14-design-notes-solid-patterns)
15. [Quick reference](#15-quick-reference)
16. [Source of truth & completeness](#source-of-truth--completeness)
17. [Production: Prophet or other models](#17-production-prophet-or-other-models)

---

## 1. Who this is for & prerequisites

**Audience:** Anyone who wants to run and test the app—no coding experience required. Use a terminal (Mac/Linux Terminal, or Windows Command Prompt / PowerShell) and follow the steps.

**Prerequisites:**
- **Java 17** or higher  
- **Maven 3.8+**

Check versions:
```bash
java -version
mvn -v
```

---

## 2. Problem we're solving

**Analogy:** Think of a chain of shops (merchants) that sell in different categories (electronics, clothing, etc.). Every sale is a small event. The business wants two things quickly:
- **“Which categories sell the most?”** (Top-N by revenue)
- **“Roughly how much will we sell next week?”** (forecast)

In a real company, that usually means many systems: message queues, transactional databases, analytics stores, caches, and batch jobs. That’s hard to explain and run locally.

**What this demo does:** It models the **same end-to-end flow** (ingest → store → aggregate → Top-N → forecast) but with **in-memory substitutes** for Kafka, Postgres, Cassandra, Redis, and S3. So you get the full picture: same logic and flow, zero infrastructure, runnable on your laptop.

---

## 3. What the app does (high level)

The app is a **multi-tenant commerce analytics pipeline**:

- **Webhook ingestion** — Accepts sales transactions (e.g. from Shopify/POS).
- **Event streaming** — Publishes each sale to an in-memory queue (simulated Kafka).
- **Ledger write** — A background consumer writes each event to a ledger (simulated Postgres) with idempotency.
- **Aggregation** — A scheduled job reads the ledger, builds **daily aggregates**, and **materializes Top-N** (e.g. weekly top 10 categories) into a serving store (simulated Cassandra) and cache (simulated Redis).
- **Top-N read** — A dedicated **read path** (QueryService) serves Top-N: cache first, then Cassandra or compute from daily aggregates for custom date ranges.
- **Reconciliation** — A separate scheduled job is dedicated to **syncing/checking** the ledger (Postgres) and the aggregate store (Cassandra).
- **Forecast pipeline** — Runs when you call `POST /api/v1/forecast/run` (on-demand). Optionally, set `demo.forecast.scheduleEnabled: true` to also run on a schedule (e.g. hourly); in production you'd typically run it daily. On demand (or by scheduler): selects merchants/categories, builds features, runs a simple “model” (baseline), quality gate, then writes forecast points to Cassandra and cache.
- **Forecast read** — Serves forecast results (cache first, then Cassandra).

**No databases or external services are required;** everything runs in-memory.

---

## 4. High-level flow (end-to-end)

Data flows in three main paths:

**Path A — Write path (ingest → ledger)**  
Client sends a sale → Webhook → Idempotency check → Publish to event bus → **Background thread** consumes bus → Writes to ledger (Postgres). Response to client is **202 Accepted** as soon as the event is published (not after ledger write).

**Path B — Aggregation (scheduled)**  
Every 15 seconds: read ledger snapshot → update daily aggregates in Cassandra → compute weekly Top-N → store in Cassandra and Redis.  
Separately, every 60 seconds: **Reconciliation** runs (dedicated to Postgres vs Cassandra sync/check).

**Path C — Read paths**  
- **Top-N:** Client asks for Top-N → **QueryService** (read-only) → AggregationService: try cache → else Cassandra or compute → return.  
- **Forecast:** Client triggers pipeline → Runner selects targets → features → “train” → quality gate → champion → inference → write to Cassandra + cache.  
  Client asks for forecast → **ForecastService** → cache first → else Cassandra → return.

So: **IngestionService** for ingestion, **TransactionWriter** for Postgres, **AggregationService** for aggregated data into Cassandra, **QueryService** for reading Top-N, **ReconciliationService** for syncing, **ForecastService** for returning forecast results. Each has a clear responsibility.

**In one paragraph (class-by-class flow):** The demo starts from MainApplication, which wires everything together (repositories, services, controllers, background jobs). A sale enters via WebhookController → IngestionService (idempotency via Redis, then publish to InMemoryEventBus). TransactionWriterService (background thread) consumes the bus and writes to PostgresLedgerRepository. AggregationService runs on a schedule: updates daily aggregates and precomputes weekly Top-N into Cassandra and Redis. For reads, AnalyticsController → QueryService (cache first, then Cassandra or compute for custom ranges). For forecasting, ForecastPipelineRunner selects targets from Cassandra, FeatureExtractionService writes features to S3 feature store, pipeline does training (baseline blob), quality gate, champion registry; BatchInferenceService writes forecasts to Cassandra (and cache). ReconciliationService runs periodically to sync/check Postgres vs Cassandra (e.g. logs that reconciliation ran).

---

## 5. Component reference: what each part does and how

For each component: **what it does**, **how it does it**, and **how it’s implemented** at a high level.

### Controllers (API layer)

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **WebhookController** | Accepts transaction POSTs. | Validates body, reads tenant from context, calls IngestionService, returns 202 + requestId. | REST `POST /api/v1/webhooks/transactions`; delegates to IngestionService. |
| **AnalyticsController** | Serves Top-N for dashboards. | Reads tenant and query params, calls QueryService, returns Top-N JSON. | REST `GET /api/v1/analytics/topn`; delegates to QueryService (read-only). |
| **ForecastController** | Triggers pipeline and serves forecast reads. | Run: calls ForecastPipelineRunner. Get: calls ForecastService. | `POST /api/v1/forecast/run` and `GET /api/v1/forecast`. |

### Ingestion path

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **IngestionService** | Ensures each transaction is processed at most once and hands it to the pipeline. | Checks idempotency key in Redis (tenant + transactionId); if new, publishes event to bus and stores idempotency marker; if duplicate, returns existing requestId. | Uses RedisCacheRepository for idempotency; InMemoryEventBus for publish. |

### Ledger write path

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **TransactionWriterService** | Writes every ingested event to the ledger. | Runs a **dedicated background thread** (started at app startup) that polls the event bus and, for each event, upserts into the ledger. | Uses InMemoryEventBus.poll() and PostgresLedgerRepository.upsertIdempotent(); thread runs in same JVM so startup is not blocked. |
| **PostgresLedgerRepository** | Stores the system-of-record list of transactions. | Idempotent upsert by (tenantId, transactionId). Exposes a snapshot of the ledger for aggregation. | In-memory Map; putIfAbsent for upsert; snapshot() returns the map. |

### Aggregation path

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **AggregationService** | Builds daily aggregates and materialized Top-N from the ledger. | **Scheduled** (e.g. every 15 s): reads ledger snapshot, for each not-yet-aggregated transaction updates daily aggregate in Cassandra, computes weekly Top-N, stores in Cassandra and Redis. Also exposes getMaterializedTopN and getTopNForCustomRange (cache → Cassandra → or compute). | Uses PostgresLedgerRepository.snapshot(), Redis “aggSeen” keys to avoid double-count, CassandraServingRepository for aggregates and Top-N, Redis for cache; Jackson for Top-N JSON in cache. |

### Read path (Top-N)

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **QueryService** | **Dedicated read path** for Top-N. | Decides: if client sent start/end → custom range (getTopNForCustomRange); else → materialized (getMaterializedTopN). Delegates to AggregationService which does cache → Cassandra → or compute. | Single responsibility: route read request to the right aggregation API; no write logic. |

### Reconciliation

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **ReconciliationService** | **Dedicated to syncing/checking** ledger (Postgres) vs aggregate store (Cassandra). | **Scheduled** (e.g. every 60 s): runs a reconciliation step (e.g. logs that reconciliation ran for a given day). Not part of aggregation; separate concern. | Has PostgresLedgerRepository and CassandraServingRepository; for demo, currently logs only; can be extended to compare totals. |

### Forecast pipeline and read

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **ForecastPipelineRunner** | Orchestrates the full forecast pipeline. | Selects targets (merchants/categories from Cassandra), then for each: build features (FeatureExtractionService) → build model JSON (trainCandidateModel, via Jackson) → quality gate (passesQualityGate) → save champion (in-memory map) → run inference (BatchInferenceService). | Holds in-memory champion registry (map); holds cassandra, s3, featureExtraction, inference; uses @Lazy for BatchInferenceService to avoid circular dependency. |
| **FeatureExtractionService** | Builds numeric features from history. | Fetches daily aggregates for merchant/category, computes avgSales, last7Avg, volatility, points; serializes with Jackson and stores in S3 feature store. | CassandraServingRepository + S3FeatureStoreRepository + ObjectMapper. |
| **BatchInferenceService** | Produces forecast points and writes them. | Loads champion from ForecastPipelineRunner, reads avgSales/volatility from model JSON (Jackson), generates daily forecast points (pred = avgSales, band from volatility), writes to Cassandra and Redis. | Uses Jackson to read model JSON; builds ForecastResponse via factory and caches as JSON. |
| **ForecastService** | **Serves forecast reads.** | Cache first (Redis); on hit return parsed ForecastResponse; on miss load from Cassandra and build response. | Same cache key shape as BatchInferenceService; Jackson for parse. |
| **ForecastPipelineScheduler** | **Optional:** runs the forecast pipeline on a schedule. | **Scheduled** (when `demo.forecast.scheduleEnabled=true`): every `demo.schedules.forecastDelayMs` ms, runs pipeline for `demo.forecast.scheduledTenantId`. | `@ConditionalOnProperty`; only active when schedule is enabled; calls ForecastPipelineRunner.run(tenantId). |

### Repositories (simulated storage)

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **PostgresLedgerRepository** | Ledger (system of record). | Idempotent upsert by (tenantId, transactionId); snapshot for aggregation. | ConcurrentHashMap. |
| **CassandraServingRepository** | Aggregates, materialized Top-N, forecasts. | Keyed maps for daily aggregates, Top-N, forecast points; listMerchants / listCategoriesForMerchant for pipeline targets. | In-memory Maps. |
| **RedisCacheRepository** | Cache and idempotency keys. | get/put with TTL; on get, if expired remove key and return null (lazy eviction). | Map of (key → value + expiry time). |
| **S3FeatureStoreRepository** | Feature store for ML. | putFeatures / getFeatures by (tenant, merchant, category). | In-memory Map. |

### Infra

| Component | What it does | How | Implementation (high level) |
|-----------|--------------|-----|-----------------------------|
| **InMemoryEventBus** | Message queue (simulated Kafka). | publish(event) enqueues; poll() blocks until an event is available. | BlockingQueue of envelopes (tenantId + event). |
| **TenantHeaderFilter** | Ensures tenant is available for the request. | Reads X-Tenant-Id, sets TenantContext, then continues the filter chain; clears context in finally. | OncePerRequestFilter. |
| **TenantContext** | Holds current tenant for the thread. | ThreadLocal; setTenantId / getTenantId / clear. | Static methods. |

---

## 6. Simulated infrastructure vs business logic

**Does “simulated” mean there’s no business logic?**  
No. **Simulated** = we replace real infrastructure (Kafka, Postgres, Cassandra, Redis, S3) with in-memory implementations so you don’t need to install them. **Business logic** (idempotency, aggregation, Top-N, cache-vs-store, forecast pipeline, quality gate, etc.) is real and unchanged.

**What is simulated:**

| Real system | In this demo |
|-------------|--------------|
| Kafka | InMemoryEventBus (queue) |
| PostgreSQL (ledger) | PostgresLedgerRepository (Map, idempotent upsert) |
| Cassandra | CassandraServingRepository (Maps) |
| Redis | RedisCacheRepository (Map with TTL) |
| S3 feature store | S3FeatureStoreRepository (Map) |

**Forecasting “model”:** We use a **baseline**, not a trained ML model. Features (avgSales, last7Avg, volatility, points) are computed from daily aggregates and serialized with Jackson; the “model” is JSON with those features; inference sets predicted sales = avgSales per day with a band (avg ± volatility). In production you’d plug in a real model (e.g. Prophet) and keep the same pipeline shape.

**Cache-vs-store:** For Top-N and forecast, we try **cache first**; on miss we read from **store** (Cassandra or compute) and can repopulate cache. Eviction is by **TTL** (lazy on get). We don’t explicitly invalidate on source change; we overwrite when we recompute (e.g. new Top-N or new forecast).

---

## 7. How to run the app (step-by-step)

**Base URL:** `http://localhost:8080`

### Option 1: Run inside IDE (simplest)

1. Open the project folder in **IntelliJ** (or **Eclipse**) as a **Maven project** (e.g. File → Open → select the folder with `pom.xml`).
2. Ensure **Java 17** is selected for the project (Project Structure / Build path).
3. Run the main class:  
   **`com.qb.analytics.MainApplication`**  
   (Right‑click the class → Run, or use the Run button.)
4. Wait until you see:  
   `Started MainApplication in X.XXX seconds`  
   The app is then ready.

**That’s it** — no database or external setup; this demo uses in-memory repositories only.

### Option 2: Run using terminal

**Prerequisites:** Java 17 and Maven installed. Check with `java -version` and `mvn -v`.

**Steps:**

1. Open a terminal and go to the project folder:  
   `cd QB-TopNService`
2. Start the application using either:
   - **Spring Boot plugin:**  
     `mvn spring-boot:run`
   - **Or build JAR and run:**  
     `mvn -q -DskipTests package`  
     `java -jar target/qb-commerce-analytics-0.0.1-SNAPSHOT.jar`
3. Wait until you see:  
   `Started MainApplication in X.XXX seconds`  
   Keep this terminal open.
4. Optional — in a **new** terminal, check health:  
   `curl http://localhost:8080/actuator/health`  
   Expect something like: `{"status":"UP"}`.

### What you'll see locally

- The app uses **in-memory storage only** (no real Kafka, Postgres, Cassandra, Redis, or S3).
- The **S3 feature store** is simulated in memory; **no `./feature-store/` folder** is created on disk.
- Feature JSON is written to the in-memory store; in production that would be S3.

---

## 8. How to test the app (step-by-step)

Use a **second terminal** (app running in the first). All commands assume port 8080.

**Step 1 — Send three transactions (webhook)**  
Run these three curls one by one. Each sends one sale.

```bash
curl -X POST "http://localhost:8080/api/v1/webhooks/transactions" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenantA" \
  -d '{"transactionId":"t1","merchantId":"m1","categoryId":"electronics","amount":1500.0,"eventTime":"2026-02-17T00:00:00Z"}'

curl -X POST "http://localhost:8080/api/v1/webhooks/transactions" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenantA" \
  -d '{"transactionId":"t2","merchantId":"m1","categoryId":"electronics","amount":500.0,"eventTime":"2026-02-17T00:05:00Z"}'

curl -X POST "http://localhost:8080/api/v1/webhooks/transactions" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenantA" \
  -d '{"transactionId":"t3","merchantId":"m1","categoryId":"clothing","amount":700.0,"eventTime":"2026-02-17T00:06:00Z"}'
```

**What happens:** Each request is accepted; idempotency is checked; a requestId is returned. In the app logs you should see the transaction writer logging ledger inserts.

**Step 2 — Wait ~15 seconds**  
Aggregation runs every 15 seconds. Waiting lets the new transactions be included in daily aggregates and Top-N. In the app terminal you should see aggregation logs.

**Step 3 — Get weekly Top-N**  
```bash
curl "http://localhost:8080/api/v1/analytics/topn?merchantId=m1&timeframe=WEEKLY&n=5" -H "X-Tenant-Id: tenantA"
```  
You should get JSON with `items`: top categories by revenue (e.g. electronics, clothing) with ranks and values.

**Step 4 — Run forecast pipeline**  
```bash
curl -X POST "http://localhost:8080/api/v1/forecast/run" -H "X-Tenant-Id: tenantA"
```  
You should get 200 OK; in logs you may see forecast pipeline and inference messages.

**Step 5 — Get forecast**  
```bash
curl "http://localhost:8080/api/v1/forecast?merchantId=m1&categoryId=electronics&horizonDays=7" -H "X-Tenant-Id: tenantA"
```  
You should get JSON with `points`: date and predicted sales (and confidence band) for the next 7 days.

---

## 9. What you'll see in the logs

- **Startup:** `Started MainApplication in ... seconds`, transaction writer thread started, Tomcat on 8080, then periodic Reconciliation and Aggregation messages.
- **While idle:** Every 30 seconds you’ll see `TransactionWriter listening for messages (no message in last 30s)` so you know the consumer is running and waiting on the queue.
- **After sending transactions:** Ingestion published to event bus; Writer ledger insert OK (or duplicate ignored).
- **After Top-N request:** QueryService path (materialized/customRange); Aggregation getMaterializedTopN source (cache/cassandra/computed).
- **After forecast run:** ForecastPipeline starting, selectTargets, FeatureExtraction, ModelEvaluation quality gate, BatchInference forecast stored.
- **Forecast read:** ForecastService cache hit or cache miss.

There is no background “forecast worker”; the pipeline runs when you call `POST /api/v1/forecast/run`. For more detail, set `logging.level.com.qb.analytics: DEBUG` in `application.yml`.

---

## 10. Configuration

In `src/main/resources/application.yml`:

| Key | Meaning | Default |
|-----|---------|---------|
| `demo.schedules.aggregationDelayMs` | Delay between aggregation runs (ms) | 15000 (15 s) |
| `demo.schedules.reconciliationDelayMs` | Delay between reconciliation runs (ms) | 60000 (60 s) |
| `demo.topn.defaultN` | Default Top-N size | 10 |
| `demo.topn.maxN` | Max Top-N size | 50 |
| `demo.schedules.forecastDelayMs` | Delay between scheduled forecast runs (ms); used only when schedule is enabled | 3600000 (1 h) |
| `demo.forecast.historyDays` | Days of history for forecasting | 90 |
| `demo.forecast.horizonDaysDefault` | Default forecast horizon (days) | 7 |
| `demo.forecast.scheduleEnabled` | If true, forecast pipeline runs on a schedule (as well as on-demand via endpoint) | false |
| `demo.forecast.scheduledTenantId` | Tenant id used when running the scheduled forecast | defaultTenant |

---

## 11. API reference

**All requests:** Include header `X-Tenant-Id: <tenantId>` (e.g. `tenantA`). Tenant can default to `defaultTenant` if not set. In a real system, also send `Authorization: Bearer <token>` (or platform signature header); this demo does not enforce auth.

**Doc vs code:** Tenant is always taken from the header `X-Tenant-Id` (see `TenantHeaderFilter`). The ingestion request body does **not** include `tenantId` or `eventId`; it uses `transactionId`, `merchantId`, `categoryId`, `amount`, `eventTime` (model `TransactionEvent`). Idempotency is keyed by `tenantId` + `transactionId`. Response fields (e.g. `rank`, `categoryId`, `value` for Top-N; `confidenceLow`/`confidenceHigh` for forecast) match the Java response models.

---

### 11.1 Webhook ingestion (transaction ingest)

**Endpoint:** `POST /api/v1/webhooks/transactions`

**Headers:** `Content-Type: application/json`, `X-Tenant-Id: <tenantId>`

**Request:**
```json
{
  "transactionId": "t1",
  "merchantId": "m1",
  "categoryId": "electronics",
  "amount": 1500.0,
  "eventTime": "2026-02-17T00:00:00Z"
}
```

**Response (202 Accepted):**
```json
{
  "status": "ACCEPTED",
  "requestId": "..."
}
```

**Behavior:** Idempotency by `tenantId` + `transactionId`. If the platform retries the same webhook, the app returns success without duplicate processing.

---

### 11.2 Analytics Top-N (dashboard read)

**Endpoint:** `GET /api/v1/analytics/topn?merchantId=m1&timeframe=WEEKLY&n=10&metric=REVENUE`

**Headers:** `X-Tenant-Id: <tenantId>`

**Query params:** `merchantId` (required), `timeframe` (WEEKLY or CUSTOM), `n` (default 10), `metric` (default REVENUE). For custom range add `start` and `end` (yyyy-MM-dd); max range 90 days (recommended).

**Request:** None (GET).

**Response (200 OK):**
```json
{
  "tenantId": "tenantA",
  "merchantId": "m1",
  "timeframe": "WEEKLY",
  "metric": "REVENUE",
  "n": 10,
  "generatedAt": "2026-02-17T02:00:00Z",
  "items": [
    { "rank": 1, "categoryId": "electronics", "value": 2000.0 },
    { "rank": 2, "categoryId": "clothing", "value": 700.0 }
  ]
}
```

---

### 11.3 Forecast (pipeline trigger + forecast read)

**Trigger pipeline**  
**Endpoint:** `POST /api/v1/forecast/run`  
**Headers:** `X-Tenant-Id: <tenantId>`  
**Request:** None (empty body).  
**Response (200 OK):** Body may be empty or a simple acknowledgment. In production a scheduler would typically run the pipeline; this endpoint is for on-demand demo use.

**Get forecast**  
**Endpoint:** `GET /api/v1/forecast?merchantId=m1&categoryId=electronics&horizonDays=7`  
**Headers:** `X-Tenant-Id: <tenantId>`  
**Request:** None (GET).

**Response (200 OK):**
```json
{
  "tenantId": "tenantA",
  "merchantId": "m1",
  "categoryId": "electronics",
  "horizonDays": 7,
  "modelUsed": "MOVING_AVERAGE_BASELINE",
  "generatedAt": "2026-02-17T02:00:00Z",
  "points": [
    { "date": "2026-02-18", "predictedSales": 1000.0, "confidenceLow": 800.0, "confidenceHigh": 1200.0 },
    { "date": "2026-02-19", "predictedSales": 1000.0, "confidenceLow": 800.0, "confidenceHigh": 1200.0 }
  ]
}
```

---

### 11.4 Health check

**Endpoint:** `GET /actuator/health`  
**Request:** None.  
**Response (200 OK):**
```json
{
  "status": "UP"
}
```

---

## 12. Repository layout

```
QB-TopNService/
├── src/main/java/com/qb/analytics/
│   ├── MainApplication.java
│   ├── controller/
│   │   ├── WebhookController.java
│   │   ├── AnalyticsController.java
│   │   └── ForecastController.java
│   ├── service/
│   │   ├── IngestionService.java
│   │   ├── TransactionWriterService.java
│   │   ├── AggregationService.java
│   │   ├── QueryService.java
│   │   └── ReconciliationService.java
│   ├── service/forecast/
│   │   ├── ForecastPipelineRunner.java
│   │   ├── ForecastPipelineScheduler.java   (optional; active when demo.forecast.scheduleEnabled=true)
│   │   ├── ForecastKeys.java
│   │   ├── ForecastService.java
│   │   ├── FeatureExtractionService.java
│   │   └── BatchInferenceService.java
│   ├── repository/
│   │   ├── PostgresLedgerRepository.java
│   │   ├── CassandraServingRepository.java
│   │   ├── RedisCacheRepository.java
│   │   └── S3FeatureStoreRepository.java
│   ├── infra/
│   │   ├── InMemoryEventBus.java
│   │   ├── TenantContext.java
│   │   └── TenantHeaderFilter.java
│   └── model/
│       ├── TransactionEvent.java
│       ├── AggregateRecord.java
│       ├── TopNResponse.java
│       ├── ForecastPoint.java
│       └── ForecastResponse.java
├── src/main/resources/
│   └── application.yml
└── QB_README.md (this file)
```

### What the `model` folder is for

The **model** folder holds **data-shape classes** (POJOs): the structures used for API request/response and for data passed between services and repositories. No business logic or I/O—just fields, getters/setters, and (where needed) validation. This keeps a single definition for each shape and lets Jackson serialize/deserialize to JSON cleanly.

| Class | Purpose | Used where |
|-------|---------|------------|
| **TransactionEvent** | Webhook request body: transactionId, merchantId, categoryId, amount, eventTime (with validation). | WebhookController (request body), IngestionService, event bus, TransactionWriter, ledger. |
| **AggregateRecord** | One daily aggregate row: tenant, merchant, category, day, revenue, units. | AggregationService and CassandraServingRepository (daily aggregates). |
| **TopNResponse** | Top-N API response: tenantId, merchantId, timeframe, metric, n, generatedAt, list of **Item** (rank, categoryId, value). | AggregationService, QueryService, AnalyticsController; also cached in Redis. |
| **ForecastPoint** | One forecast point: date, predictedSales, confidenceLow, confidenceHigh. | BatchInferenceService, ForecastResponse, Cassandra. |
| **ForecastResponse** | Forecast API response: tenantId, merchantId, categoryId, horizonDays, modelUsed, generatedAt, list of ForecastPoint. | ForecastService, BatchInferenceService (cache), ForecastController. |

So: **model** = *what* the data looks like; **service** / **controller** = *what we do* with it.

---

## 13. Flow diagrams (ingestion, Top-N, forecast)

**Ingestion (webhook → ledger)**  
Client → TenantHeaderFilter (set tenant) → WebhookController → IngestionService (idempotency in Redis; if new, publish to bus) → 202 + requestId.  
Separately: TransactionWriterService (background thread) polls bus → PostgresLedgerRepository.upsertIdempotent.

**Top-N read**  
Client → AnalyticsController → QueryService.getTopN (if start/end → getTopNForCustomRange; else getMaterializedTopN) → AggregationService (cache → Cassandra → or compute from daily aggregates) → JSON response.

**Forecast pipeline (trigger)**  
Client → ForecastController → ForecastPipelineRunner.run: selectTargets → for each (merchant, category): FeatureExtraction → trainCandidateModel → passesQualityGate → saveChampion → BatchInferenceService.runFor → write to Cassandra + Redis.

**Forecast read**  
Client → ForecastController → ForecastService.getForecast: Redis cache → if miss, Cassandra → build response → JSON.

---

## 14. Design notes (SOLID, patterns)

- **Single responsibility:** Ingestion (ingest), TransactionWriter (ledger), Aggregation (aggregates + Top-N), QueryService (read Top-N), Reconciliation (sync), ForecastService (read forecast), etc.
- **Layered design:** Controller → Service → Repository.
- **Pipeline/orchestration:** ForecastPipelineRunner sequences forecast steps; champion registry is an in-memory map inside the runner (no separate ModelRegistry interface).
- **Forecast JSON:** All forecast feature and model JSON is built with Jackson (ObjectMapper), not hand-built strings.
- **Strategy-like:** QueryService chooses materialized vs custom-range path.
- **Repository pattern:** All storage behind repository interfaces (in-memory implementations).
- **Scheduler:** AggregationService and ReconciliationService run on fixed delays.
- **Producer/consumer:** InMemoryEventBus + TransactionWriterService.

---

## 15. Quick reference

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/webhooks/transactions` | Ingest a transaction |
| GET | `/api/v1/analytics/topn?merchantId=...&timeframe=WEEKLY&n=10` | Get Top-N (optional start, end) |
| POST | `/api/v1/forecast/run` | Trigger forecast pipeline |
| GET | `/api/v1/forecast?merchantId=...&categoryId=...&horizonDays=7` | Get forecast |
| GET | `/actuator/health` | Health check |

**Header:** `X-Tenant-Id: tenantA` (or any tenant id) on API requests.

---

## 16. Source of truth & completeness

This readme is intended as the **source of truth** for the QB-TopNService codebase. It covers:

| Area | What the readme provides |
|------|---------------------------|
| **Purpose & audience** | Who the app is for, what problem it solves, one-sentence and one-paragraph summaries. |
| **Architecture** | High-level flow (paths A/B/C), class-by-class narrative, component table (what/how/implementation) for every controller, service, repository, and infra class. |
| **Run & test** | Step-by-step run (IDE + terminal) and test (webhook → wait → Top-N → forecast run → forecast get). |
| **Configuration** | All `demo.*` keys and defaults matching `application.yml`. |
| **API** | Full request/response spec per endpoint (webhook, Top-N, forecast trigger, forecast get, health); doc-vs-code and headers. |
| **Repository layout** | Directory tree of all Java files and `application.yml`; explanation of the `model` folder and each model class. |
| **Flow diagrams** | Ingestion, Top-N read, forecast pipeline, forecast read. |
| **Design** | SOLID/patterns, forecast JSON (Jackson), champion registry, cache-vs-store. |

**Keeping it accurate:** When you add or remove a class, change a config key, or change an API contract, update the corresponding section (component reference, repository layout, config table, or API reference) so the readme stays the single place to understand the system.

---

## 17. Production: Prophet or other models

**Can we use Prophet in the forecast service to simplify and reduce code?**

**Prophet** (Meta’s time-series library) is **Python (and R) only**; there is no native Java Prophet library. So:

- **Option A — Python sidecar/service:** Run a small Python service that uses Prophet (e.g. `fit()` on history, `predict()` for horizon). The Java app would call it via HTTP or gRPC, passing (tenant, merchant, category, history data or feature store path) and receiving forecast points. **Effect:** The *model* logic moves to Python (Prophet does trend/seasonality/holidays); the Java side gets a *client* and serialization. Total code is not less: you add a service, a contract, and deployment. Forecast *quality* can improve.
- **Option B — Keep baseline in Java:** The current demo uses a **moving-average baseline** in Java (avgSales, volatility band). No extra runtime or network. Good for demo and for “plug in a real model later” without changing the pipeline shape.

**Recommendation:** For this **demo**, the readme already states: *“In production you’d plug in a real model (e.g. Prophet) and keep the same pipeline shape.”* The pipeline (select targets → features → train → quality gate → champion → inference → store/cache) stays the same; only the “train” and “inference” steps would call out to Prophet (or another model service) instead of the in-memory baseline. So: **yes, Prophet can be integrated** by adding a Prophet-backed service and replacing the baseline in the pipeline; it **does not** make the Java app “less coded”—it moves model logic to Python and adds integration code. For a Java-only, minimal-dependency demo, the current baseline is the right choice.

---

*This document is the single master reference. All content from Additional_Info.md has been merged here (elevator pitch, class-by-class flow, API spec details, run steps, doc-vs-code). For a minimal copy-paste API cheat sheet, see ApiSpec.md. Nothing is left in the other docs that is not covered here.*
