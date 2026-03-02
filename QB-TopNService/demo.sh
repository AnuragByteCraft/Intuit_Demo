#!/bin/bash
# =============================================================================
# QB Top-N Demo — March 3, 2026
# Logic: Prophet when history >= 90 days; else Moving Average
# Part 1: Shopify (few days) → Moving Avg + TopN
# Part 2: Amazon (90 days) → Prophet + TopN
# For Prophet: python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt
# =============================================================================

BASE="http://localhost:8080"
pretty_json() { if command -v jq &>/dev/null; then jq .; else cat; fi; }

echo "============================================================================="
echo "QB Top-N Demo | Health-Check | Ingestion | TopN | Forecasting"
echo "============================================================================="
echo ""
echo "0. Health Check"
echo "============================================================================="
if ! curl -sf "$BASE/actuator/health" >/dev/null; then
  echo "ERROR: App not reachable at $BASE"
  echo "Start the app first in another terminal: mvn spring-boot:run"
  exit 1
fi
curl -s "$BASE/actuator/health" | pretty_json

echo ""
echo "#############################################################################"
echo "# PART 1: SHOPIFY — Few days of data → Moving Average + TopN"
echo "#############################################################################"
echo ""
echo "1a. INGESTION — Shopify/store1 (8 transactions, ~3 days)"
echo "============================================================================="
INGEST1=$(curl -s -X POST "$BASE/api/v1/webhooks/transactions" \
  -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-1","categoryId":"electronics","amount":2000.0,"eventTime":"2026-03-03T09:00:00Z"}')
echo "$INGEST1"
echo "$INGEST1" | grep -q ALREADY_PROCESSED && echo ">>> NOTE: Restart app for fresh data. <<<"
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-2","categoryId":"electronics","amount":1500.0,"eventTime":"2026-03-03T10:30:00Z"}' && echo ""
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-3","categoryId":"electronics","amount":500.0,"quantity":4,"eventTime":"2026-03-03T14:00:00Z"}' && echo ""
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-4","categoryId":"clothing","amount":1200.0,"eventTime":"2026-03-03T11:00:00Z"}' && echo ""
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-5","categoryId":"clothing","amount":800.0,"eventTime":"2026-03-02T15:00:00Z"}' && echo ""
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-6","categoryId":"home-garden","amount":950.0,"eventTime":"2026-03-02T12:00:00Z"}' && echo ""
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-7","categoryId":"sports","amount":600.0,"eventTime":"2026-03-02T09:00:00Z"}' && echo ""
curl -s -X POST "$BASE/api/v1/webhooks/transactions" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -H "X-Merchant-Id: store1" \
  -d '{"transactionId":"txn-8","categoryId":"books","amount":250.0,"quantity":2,"currency":"USD","eventTime":"2026-03-03T16:00:00Z"}' && echo ""

echo ">>> WAIT ~15 seconds for aggregation... <<<"
sleep 16

echo ""
echo "1b. TOP-N — Shopify/store1 (REVENUE)"
echo "============================================================================="
curl -s "$BASE/api/v1/analytics/topn?merchantId=store1&timeframe=WEEKLY&metric=REVENUE&n=5" -H "X-Tenant-Id: shopify" | pretty_json

echo ""
echo "1c. RUN FORECAST — Shopify (history < 90 days → Moving Average)"
echo "============================================================================="
curl -s -X POST "$BASE/api/v1/forecast/run" -H "Content-Type: application/json" -H "X-Tenant-Id: shopify" -d '{}' | pretty_json

echo ""
echo "1d. GET FORECAST — shopify electronics (MOVING_AVERAGE_BASELINE)"
echo "============================================================================="
curl -s "$BASE/api/v1/forecast?merchantId=store1&categoryId=electronics&horizonDays=7" -H "X-Tenant-Id: shopify" | pretty_json

echo ""
echo "#############################################################################"
echo "# PART 2: AMAZON — 90 days of data → Prophet + TopN"
echo "#############################################################################"
echo ""
echo "2a. INGESTION — Amazon/seller1 (90 transactions, 90 days for Prophet)"
echo "============================================================================="
echo "Ingesting 90 days of books data (Dec 4 2025 - Mar 3 2026)..."
for i in $(seq 0 89); do
  d=$(python3 -c "from datetime import date, timedelta; print((date(2025,12,4) + timedelta(days=$i)).strftime('%Y-%m-%d'))" 2>/dev/null) || d="2026-01-01"
  amt=$(( 30 + (i % 50) ))
  curl -s -X POST "$BASE/api/v1/webhooks/transactions" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: amazon" -H "X-Merchant-Id: seller1" \
    -d "{\"transactionId\":\"amz-books-$i\",\"categoryId\":\"books\",\"amount\":$amt,\"eventTime\":\"${d}T12:00:00Z\"}" >/dev/null
done
echo "Done (90 transactions)."

echo ">>> WAIT ~15 seconds for aggregation... <<<"
sleep 16

echo ""
echo "2b. TOP-N — Amazon/seller1 (REVENUE)"
echo "============================================================================="
curl -s "$BASE/api/v1/analytics/topn?merchantId=seller1&timeframe=WEEKLY&metric=REVENUE&n=5" -H "X-Tenant-Id: amazon" | pretty_json

echo ""
echo "2c. RUN FORECAST — Amazon (history >= 90 days → Prophet)"
echo "============================================================================="
curl -s -X POST "$BASE/api/v1/forecast/run" -H "Content-Type: application/json" -H "X-Tenant-Id: amazon" -d '{}' | pretty_json

echo ""
echo "2d. GET FORECAST — amazon books (PROPHET)"
echo "============================================================================="
curl -s "$BASE/api/v1/forecast?merchantId=seller1&categoryId=books&horizonDays=7" -H "X-Tenant-Id: amazon" | pretty_json

echo ""
echo "============================================================================="
echo "Demo complete."
echo "============================================================================="
