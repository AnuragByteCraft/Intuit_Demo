# mvn spring-boot:run

chmod +x demo.sh
./demo.sh

# QB Top-N Demo — March 3, 2026
# Logic: Prophet when history >= 90 days; else Moving Average
# Part 1: Shopify (few days) → Moving Avg. Part 2: Amazon (90 days) → Prophet

# -----------------------------------------------------------------------------
# 0. Health
# -----------------------------------------------------------------------------

curl -s http://localhost:8080/actuator/health

# -----------------------------------------------------------------------------
# 1. INGESTION (8 txns for shopify/store1 + 1 for amazon/seller1)
# Top-N REVENUE: electronics 4000, clothing 2000, home-garden 950, sports 600, books 250
# Top-N UNITS: electronics 6, clothing 2, home-garden 1, sports 1, books 2
# -----------------------------------------------------------------------------

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H 
"X-Merchant-Id: store1" -d '{"transactionId":"txn-1","categoryId":"electronics","amount":2000.0,"eventTime":"2026-03-03T09:00:00Z"}'

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-2","categoryId":"electronics","amount":1500.0,"eventTime":"2026-03-03T10:30:00Z"}'

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-3","categoryId":"electronics","amount":500.0,"quantity":4,"eventTime":"2026-03-03T14:00:00Z"}'
curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-4","categoryId":"clothing","amount":1200.0,"eventTime":"2026-03-03T11:00:00Z"}'

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-5","categoryId":"clothing","amount":800.0,"eventTime":"2026-03-02T15:00:00Z"}'

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-6","categoryId":"home-garden","amount":950.0,"eventTime":"2026-03-01T12:00:00Z"}'

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-7","categoryId":"sports","amount":600.0,"eventTime":"2026-03-02T09:00:00Z"}'

curl -s -X POST "http://localhost:8080/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" -d '{"transactionId":"txn-8","categoryId":"books","amount":250.0,"quantity":2,"currency":"USD","eventTime":"2026-03-03T16:00:00Z"}'

# For Amazon Prophet demo: ingest 90 days (run demo.sh Part 2, or use loop in demo.sh)

# WAIT ~15 seconds for aggregation, then:

# -----------------------------------------------------------------------------
# 2. TOP-N — WEEKLY REVENUE & UNITS — electronics 4000, clothing 2000, home-garden 950, sports 600, books 250
# -----------------------------------------------------------------------------

curl -s "http://localhost:8080/api/v1/analytics/topn?merchantId=store1&timeframe=WEEKLY&metric=REVENUE&n=5" -H "X-Tenant-Id: shopify"

curl -s "http://localhost:8080/api/v1/analytics/topn?merchantId=store1&timeframe=WEEKLY&metric=UNITS&n=5" -H "X-Tenant-Id: shopify"


# -----------------------------------------------------------------------------
# 3. RUN FORECAST
# -----------------------------------------------------------------------------

curl -s -X POST "http://localhost:8080/api/v1/forecast/run" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -d '{}'

# -----------------------------------------------------------------------------
# 4. GET FORECAST — electronics (predicted 4000/day), clothing (predicted 1000/day)
# -----------------------------------------------------------------------------

curl -s "http://localhost:8080/api/v1/forecast?merchantId=store1&categoryId=electronics&horizonDays=7" -H "X-Tenant-Id: shopify"
curl -s "http://localhost:8080/api/v1/forecast?merchantId=store1&categoryId=clothing&horizonDays=7" -H "X-Tenant-Id: shopify"
