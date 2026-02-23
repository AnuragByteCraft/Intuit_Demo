#!/usr/bin/env python3
"""
Prophet forecast script: read JSON from stdin, print forecast points JSON to stdout.
Input: {"history": [{"date": "yyyy-mm-dd", "value": <number>}, ...], "horizonDays": 7}
Output: {"points": [{"date": "yyyy-mm-dd", "predictedSales": <n>, "confidenceLow": <n>, "confidenceHigh": <n>}, ...]}
"""
import json
import sys
from datetime import datetime, timedelta

def main():
    try:
        raw = sys.stdin.read()
        req = json.loads(raw)
        history = req.get("history", [])
        horizon_days = int(req.get("horizonDays", 7))
    except (json.JSONDecodeError, KeyError, ValueError) as e:
        print(json.dumps({"points": [], "error": str(e)}), flush=True)
        sys.exit(1)

    if not history or horizon_days <= 0:
        print(json.dumps({"points": []}), flush=True)
        sys.exit(0)

    try:
        import pandas as pd
        from prophet import Prophet
    except ImportError as e:
        print(json.dumps({"points": [], "error": f"Import failed: {e}"}), flush=True)
        sys.exit(1)

    df = pd.DataFrame(history)
    df = df.rename(columns={"date": "ds", "value": "y"})
    df["ds"] = pd.to_datetime(df["ds"])

    m = Prophet(interval_width=0.9)
    m.fit(df)

    last_date = df["ds"].max()
    future_dates = [last_date + timedelta(days=i) for i in range(1, horizon_days + 1)]
    future = pd.DataFrame({"ds": future_dates})
    forecast = m.predict(future)

    points = []
    for _, row in forecast.iterrows():
        d = row["ds"].strftime("%Y-%m-%d")
        yhat = float(row["yhat"])
        low = float(row.get("yhat_lower", yhat - 1))
        high = float(row.get("yhat_upper", yhat + 1))
        points.append({
            "date": d,
            "predictedSales": round(yhat, 2),
            "confidenceLow": round(low, 2),
            "confidenceHigh": round(high, 2),
        })

    print(json.dumps({"points": points}), flush=True)


if __name__ == "__main__":
    main()
