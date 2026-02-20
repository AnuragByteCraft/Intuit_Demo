# API Spec (Demo)

All requests require header:
- `X-Tenant-Id: <tenantId>`

---

## 1) Webhook ingestion
POST `/api/v1/webhooks/transactions`

Request:
```json
{
  "transactionId": "t1",
  "merchantId": "m1",
  "categoryId": "electronics",
  "amount": 1500.0,
  "eventTime": "2026-02-17T00:00:00Z"
}
```

Response (202):
```json
{
  "status": "ACCEPTED",
  "requestId": "..."
}
```

---

## 2) Analytics Top-N
GET `/api/v1/analytics/topn?merchantId=m1&timeframe=WEEKLY&n=10&metric=REVENUE`

Optional for custom range:
- `start=2026-02-01`
- `end=2026-02-17`

Response (200):
```json
{
  "tenantId": "tenantA",
  "merchantId": "m1",
  "timeframe": "WEEKLY",
  "metric": "REVENUE",
  "n": 10,
  "generatedAt": "2026-02-17T02:00:00Z",
  "items": [
    {"rank": 1, "categoryId": "electronics", "value": 2000.0},
    {"rank": 2, "categoryId": "clothing", "value": 700.0}
  ]
}
```

---

## 3) Forecast
POST `/api/v1/forecast/run`
- Triggers the forecast pipeline for demo (normally a scheduler would run it)

GET `/api/v1/forecast?merchantId=m1&categoryId=electronics&horizonDays=7`

Response (200):
```json
{
  "tenantId": "tenantA",
  "merchantId": "m1",
  "categoryId": "electronics",
  "horizonDays": 7,
  "modelUsed": "MOVING_AVERAGE_BASELINE",
  "points": [
    {"date":"2026-02-18","predictedSales": 1000.0, "confidenceLow": 800.0, "confidenceHigh": 1200.0}
  ]
}
```
