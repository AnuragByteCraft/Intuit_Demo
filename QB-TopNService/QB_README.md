# QB-TopNService

Multi-tenant commerce analytics: ingest transactions via webhook, store in a ledger, build daily aggregates and Top-N by category, run a forecast pipeline (Moving Average or Prophet). All storage is **in-memory** for the demo (no Kafka, Postgres, Cassandra, Redis, or S3 required). Prophet uses a local Python script when history is sufficient.

---

## What the system does

- **Ingest** — Webhook accepts transactions; idempotency (Redis); event bus → background writer → ledger (Postgres).
- **Aggregate** — Scheduled job: ledger → daily aggregates + materialized Top-N (DAILY/WEEKLY/MONTHLY/YEARLY) → Cassandra + Redis.
- **Top-N read** — QueryService: cache → Cassandra or compute; materialized or custom date range (max 90 days).
- **Forecast** — Pipeline builds features per (merchant, category); if points &lt; threshold → Moving Average baseline; else → Prophet (Python script). Results written to store + cache. Read path: cache → Cassandra (no model run).

---

## Repository structure

```
QB-TopNService/
├── src/main/java/com/qb/analytics/
│   ├── MainApplication.java
│   ├── config/          # ForecastConfig, WebConfig
│   ├── controller/      # Webhook, Analytics (Top-N), Forecast
│   ├── service/         # Ingestion, TransactionWriter, Aggregation, Query, Reconciliation
│   ├── service/forecast/  # PipelineRunner, ForecastService, FeatureExtraction, MovingAverage, Prophet, ForecastWrite, ProphetScriptRunner
│   ├── repository/      # Postgres (ledger), Cassandra (aggregates, Top-N, forecasts), Redis (cache, idempotency), S3 (features)
│   ├── infra/           # InMemoryEventBus, TenantContext, TenantHeaderFilter
│   └── model/           # TransactionEvent, AggregateRecord, TopNResponse, ForecastPoint, ForecastResponse
├── src/test/java/       # Unit tests (repositories, IngestionService, AggregationService, forecast services, ForecastConfig)
├── src/main/resources/application.yml
├── scripts/             # prophet_predict.py, requirements.txt
├── INGESTION_FLOW.md
├── TOP_N_FLOW.md
├── FORECAST_GET_FLOW.md
├── FORECAST_RUN_FLOW.md
└── FORECAST_MODELS.md
```

---

## Layer responsibilities

| Layer | Responsibility |
|-------|----------------|
| **Controller** | HTTP: webhook (POST; headers `X-Tenant-Id`, `X-Merchant-Id`; body transactionId, categoryId, amount, eventTime), Top-N (GET), forecast run (POST), forecast get (GET). Tenant from `X-Tenant-Id`. |
| **Service** | Business flow: idempotency, publish, aggregate, query, forecast pipeline (feature build → baseline or Prophet → write). |
| **Repository** | Storage abstraction; this demo uses in-memory maps (ConcurrentHashMap, etc.). |

---

## How to run

1. **Prerequisites:** Java 17+, Maven 3.8+ (`java -version`, `mvn -v`).
2. From project root: `mvn spring-boot:run` (or run `MainApplication` from IDE).
3. App starts on `http://localhost:8080`; no external DBs. For Prophet: `python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt`.

---

## How to test

Use a terminal with the app running on `http://localhost:8080`. **Ingest:** send **`X-Tenant-Id`** and **`X-Merchant-Id`** in headers; body has `transactionId`, `categoryId`, `amount`, `eventTime` (no merchantId in body). **Other APIs:** send **`X-Tenant-Id`**; Top-N and Forecast also use query param `merchantId`.

**Quick reference:**

| Step | Method | Path | Key headers / params | Success response |
|------|--------|------|---------------------|------------------|
| 0 | GET | `/actuator/health` | — | `{"status":"UP"}` |
| 1 | POST | `/api/v1/webhooks/transactions` | `X-Tenant-Id`, `X-Merchant-Id`; body: one JSON per txn | 202 `{"status":"ACCEPTED","requestId":"<uuid>"}` |
| 2 | GET | `/api/v1/analytics/topn` | `X-Tenant-Id`; `merchantId`, `timeframe=WEEKLY`, `metric=REVENUE`, `n=5` | 200 Top-N JSON with `items` |
| 3 | GET | `/api/v1/forecast` | `X-Tenant-Id`; `merchantId`, `categoryId`, `horizonDays=7` | 200 Forecast JSON with `points` (after step 4) |
| 4 | POST | `/api/v1/forecast/run` | `X-Tenant-Id`; body `{}` | 200 `{"status":"OK","message":"Forecast pipeline executed for tenant=shopify"}` |

**Demo date:** Curls below are set for **March 3rd 2026**. eventTime uses 2026-03-03 so Get Top-N (WEEKLY) and forecast behave correctly on demo day. If you test before March 3rd, Top-N still returns data: when the “current” week has no data, the service uses the week that contains your ingested events (e.g. 2026-03-03).

**Demo order:** 0 Health → 1 Ingest (all 4) → wait ~15 s → 2 Get Top-N (WEEKLY) → 4 Run forecast → 3 Get forecast.

---

### 0. Check health

```bash
curl -s http://localhost:8080/actuator/health
```

**Expected response (200 OK):**
```json
{"status":"UP"}
```

---

### 1. Ingest sample data (demo — one request per transaction)

**Important:** The API accepts **one transaction per HTTP request**. Do not put multiple JSON objects in one body (e.g. `{"t1":...}&{"t2":...}`) — the server will only parse the first and the rest are ignored. Send **4 separate requests** (4 curls or 4 Postman requests).

Tenant/merchant from **headers** (`X-Tenant-Id`, `X-Merchant-Id`). Body: `transactionId`, `categoryId`, `amount`, `eventTime`. All events use **2026-03-03** so they fall in the current week on demo day (March 3rd 2026).

**Option A — one line (runs 4 separate curls):**
```bash

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"t1","categoryId":"electronics","amount":1500.0,"eventTime":"2026-03-03T00:00:00Z"}'; 

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"t2","categoryId":"electronics","amount":500.0,"eventTime":"2026-03-03T00:05:00Z"}'; 

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"t3","categoryId":"clothing","amount":700.0,"eventTime":"2026-03-03T00:06:00Z"}'; 

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: amazon" -H "X-Merchant-Id: seller1" -d '{"transactionId":"t4","categoryId":"books","amount":25.0,"eventTime":"2026-03-03T10:00:00Z"}'
```
**Expected response (202 Accepted)** — new transaction:
```json
{"status":"ACCEPTED","requestId":"<uuid>"}
```

**Expected response (200 OK)** — if you resend the **exact same** request (same `X-Tenant-Id`, `X-Merchant-Id`, and body with same `transactionId`):
```json
{"status":"ALREADY_PROCESSED","requestId":"<original-requestId>"}
```
Duplicate is detected per (tenant + merchant + transactionId). After an app restart the in-memory store is empty, so the first ingest of t1 is always ACCEPTED. **Missing `X-Merchant-Id`** returns **400** with `{"error":"Missing required header: X-Merchant-Id"}`.

---

### 2. Get Top-N (WEEKLY)

For **store1** you need to have ingested **all 3** transactions (t1, t2, t3) so that WEEKLY shows two categories: electronics (2000) and clothing (700). Wait **~15 seconds** after the last ingest so aggregation has run, then:

```bash
curl -s "http://localhost:8080/api/v1/analytics/topn?merchantId=store1&timeframe=WEEKLY&metric=REVENUE&n=5" -H "X-Tenant-Id: shopify"
```

**Expected response (200 OK)** — on demo day (March 3rd 2026) bucketStart is Monday of that week (`2026-03-02`):
```json
{
  "tenantId": "shopify",
  "merchantId": "store1",
  "timeframe": "WEEKLY",
  "bucketStart": "2026-03-02",
  "metric": "REVENUE",
  "n": 5,
  "generatedAt": "<iso-timestamp>",
  "items": [
    {"rank": 1, "categoryId": "electronics", "value": 2000.0},
    {"rank": 2, "categoryId": "clothing", "value": 700.0}
  ]
}
```

---

### 3. Get forecast

Returns stored forecast. Run **after** step 4 (run forecast) to see data.

```bash
curl -s "http://localhost:8080/api/v1/forecast?merchantId=store1&categoryId=electronics&horizonDays=7" -H "X-Tenant-Id: shopify"
```

**Expected response (200 OK)** — after running step 4. With demo data (electronics 2000 for one day), baseline uses avg 2000 and volatility 0, so 7 points with same value. Dates are from the day after server date (e.g. March 3rd → 2026-03-04 … 2026-03-10). Response also includes `"data"` (same as `points`).
```json
{
  "tenantId": "shopify",
  "merchantId": "store1",
  "categoryId": "electronics",
  "horizonDays": 7,
  "modelUsed": "MOVING_AVERAGE_BASELINE",
  "generatedAt": "<iso-timestamp>",
  "points": [
    {"date": "2026-03-04", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0},
    {"date": "2026-03-05", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0},
    {"date": "2026-03-06", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0},
    {"date": "2026-03-07", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0},
    {"date": "2026-03-08", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0},
    {"date": "2026-03-09", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0},
    {"date": "2026-03-10", "predictedSales": 2000.0, "confidenceLow": 2000.0, "confidenceHigh": 2000.0}
  ]
}
```
The response also includes `"data"` (same content as `points`). When testing before March 3rd, `date` values will be server date + 1 … + 7. `modelUsed` may be `PROPHET` if history ≥ minHistoryDaysForProphet.

---

### 4. Run forecast

Triggers the forecast pipeline for the tenant. Run this **before** step 3 (Get forecast) so step 3 returns data.

```bash
curl -s -X POST "http://localhost:8080/api/v1/forecast/run" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -d '{}'
```

**Expected response (200 OK):**
```json
{"status":"OK","message":"Forecast pipeline executed for tenant=shopify"}
```

Then run **step 3 (Get forecast)** to see the generated points.

---

**Unit tests:** `mvn test` — covers repositories (Redis, Postgres), IngestionService, AggregationService, forecast services (ForecastWrite, MovingAverage, FeatureExtraction), ForecastConfig.

---

## Configuration

Main keys in `src/main/resources/application.yml`: `demo.schedules.aggregationDelayMs` (15s), `demo.forecast.historyDays`, `demo.forecast.horizonDaysDefault`, `demo.forecast.minHistoryDaysForProphet`, Prophet script path. For Prophet, use a Python venv and `pip install -r scripts/requirements.txt`.

---

## Flow docs

- [INGESTION_FLOW.md](INGESTION_FLOW.md) — Webhook → idempotency → bus → ledger.
- [TOP_N_FLOW.md](TOP_N_FLOW.md) — Top-N read (materialized / custom range).
- [FORECAST_GET_FLOW.md](FORECAST_GET_FLOW.md) — Get forecast (read-only).
- [FORECAST_RUN_FLOW.md](FORECAST_RUN_FLOW.md) — Run forecast pipeline.

For forecast model details and production options: [FORECAST_MODELS.md](FORECAST_MODELS.md).

For the full master reference (component table, API spec, run/test step-by-step, design notes): [FULL_READ_ME.md](FULL_READ_ME.md).
