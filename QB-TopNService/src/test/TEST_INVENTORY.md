# Unit test inventory

All tests live under `src/test/java/com/qb/analytics/` (same package structure as main). Run with: `mvn test`.

## Current test classes (8)

| Test class | Package | Covers |
|------------|---------|--------|
| **ForecastConfigTest** | config | Defaults and setters for `demo.forecast.*` |
| **RedisCacheRepositoryTest** | repository | putIfAbsent (first vs duplicate), get, put |
| **PostgresLedgerRepositoryTest** | repository | upsertIdempotent (insert vs duplicate), snapshot is a copy |
| **IngestionServiceTest** | service | First ingest → ACCEPTED + publish to bus; duplicate → ALREADY_PROCESSED, same requestId |
| **AggregationServiceTest** | service | bucketRange for DAILY, WEEKLY, MONTHLY, YEARLY; invalid timeframe throws |
| **ForecastWriteServiceTest** | service.forecast | Cache key format; write persists to Cassandra and Redis |
| **MovingAverageForecastServiceTest** | service.forecast | Quality gate (configurable minPointsForBaseline); runFor with features returns points |
| **FeatureExtractionServiceTest** | service.forecast | getHistoryJson from S3 when present; buildAndStoreFeatures stores history |

## Main code with no dedicated test class

These are covered indirectly by the above or not unit-tested:

- **QueryService** — read path; delegates to AggregationService (tested there).
- **ForecastPipelineRunner** — orchestration; uses FeatureExtraction, MovingAverage/Prophet, ForecastWrite (each tested).
- **ForecastService** — read path; fetches from Cassandra/Redis (no standalone test).
- **ProphetForecastService** / **ProphetScriptRunner** — depend on Python script; not in current unit suite.
- **ReconciliationService** — scheduled job; no unit test.
- **TransactionWriterService** — event bus consumer; no unit test.
- **CassandraServingRepository** — in-memory maps; used by AggregationServiceTest, ForecastWriteServiceTest, FeatureExtractionServiceTest.
- **S3FeatureStoreRepository** — in-memory map; used by forecast tests.
- **Controllers** (Webhook, Analytics, Forecast, Root) — no controller tests; covered by manual/curl.

If you had additional test files in the past (e.g. QueryServiceTest, ForecastPipelineRunnerTest), they are not in the repo now; you can add them following the same style (JUnit 5, AssertJ, no Spring context).
