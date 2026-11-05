"""
Walk-forward test of candidate momentum features against the current feature set.

Offline research only: it reads ml/training_data.csv, never touches the shipped model or the app.
Uses scikit-learn's HistGradientBoostingRegressor as a stand-in for ydf (no ydf wheel for newer
Python); depth 4 / 300 trees / low learning rate mirror the shipped settings, so the comparison
between feature sets is meaningful even though absolute numbers will differ from the shipped model.

For each quarterly fold: train on every row dated before the fold (minus a 14-day purge so no
training label overlaps the test period), predict the fold, then pool all folds' predictions.
Reported per horizon and feature set: directional accuracy, accuracy on the pooled window vs the
always-up baseline, correlation, calibration slope, and MAE vs a "no change" forecast.

    python ml/walk_forward.py
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

CSV = Path(__file__).parent / "training_data.csv"
TARGET = "target_change_pct"
FIRST_TEST = pd.Timestamp("2024-10-01")  # leaves ~1 year of training before the first fold
PURGE_DAYS = 14
HORIZON_GROUPS = {1: range(1, 4), 5: range(4, 8)}  # evaluated horizon -> training group (matches train_grouped.py)

# The current shipped feature set (gbpUsd and oilPrice levels deliberately excluded, as in train_model.py).
BASE = [
    "us2y", "vix", "gbpUsdChange5d", "oilChange5d", "rsi14", "daysAhead", "brentChange5d",
    "gbpUsdChange1d", "gbpUsdChange10d", "gbpUsdChange20d", "gbpUsdRealVol10d",
    "us2yChange5d", "vixChange5d", "usdBasketChange1d", "usdBasketChange5d",
    "ukRate", "spread2y", "spread2yChange1d", "spread2yChange5d", "spread2yChange10d",
]


def candidate_features(daily: pd.DataFrame) -> pd.DataFrame:
    """New momentum features, from the GBP/USD close series only (zero new fetches)."""
    p = daily["gbpUsd"]
    ret = p.pct_change(fill_method=None)
    out = pd.DataFrame(index=daily.index)
    vol10 = ret.rolling(10).std()
    vol60 = ret.rolling(60).std()
    ch5, ch10, ch20 = daily["gbpUsdChange5d"], daily["gbpUsdChange10d"], daily["gbpUsdChange20d"]

    # A. volatility-scaled momentum (a z-score of the trend)
    out["volScaled5d"] = (ch5 / 100) / vol10
    out["volScaled20d"] = (ch20 / 100) / vol10
    # B. acceleration: this 5d move minus the 5d move before it, and short-vs-long slope gap
    prev5 = ((1 + ch10 / 100) / (1 + ch5 / 100) - 1) * 100
    out["accel5d"] = ch5 - prev5
    out["slopeGap5v20"] = ch5 / 5 - ch20 / 20
    # C. moving-average gaps and a slower trend
    out["priceVsSma20"] = p / p.rolling(20).mean() - 1
    out["sma10VsSma50"] = p.rolling(10).mean() / p.rolling(50).mean() - 1
    out["change60d"] = p.pct_change(60, fill_method=None) * 100
    # D. where price sits in its recent range, and how far below its 20-day high
    lo20, hi20 = p.rolling(20).min(), p.rolling(20).max()
    out["rangePos20d"] = (p - lo20) / (hi20 - lo20)
    out["belowHigh20d"] = p / hi20 - 1
    # E. volatility regime
    out["volRatio10v60"] = vol10 / vol60
    return out


GROUPS = {
    "A volScaled": ["volScaled5d", "volScaled20d"],
    "B accel": ["accel5d", "slopeGap5v20"],
    "C maGap": ["priceVsSma20", "sma10VsSma50", "change60d"],
    "D range": ["rangePos20d", "belowHigh20d"],
    "E volRegime": ["volRatio10v60"],
}


def fit_predict(train: pd.DataFrame, test: pd.DataFrame, cols: list[str]) -> np.ndarray:
    model = HistGradientBoostingRegressor(
        max_depth=4, max_iter=300, learning_rate=0.015, min_samples_leaf=20,
        l2_regularization=1.0, random_state=0,
    )
    model.fit(train[cols], train[TARGET])
    return model.predict(test[cols])


def run(frame: pd.DataFrame, cols: list[str], horizon: int) -> pd.DataFrame:
    group = HORIZON_GROUPS[horizon]
    dates = frame["date"]
    folds = pd.date_range(FIRST_TEST, dates.max(), freq="QS")
    preds = []
    for start in folds:
        end = start + pd.offsets.QuarterBegin(startingMonth=1)
        train = frame[(dates < start - pd.Timedelta(days=PURGE_DAYS)) & frame["daysAhead"].isin(group)]
        test = frame[(dates >= start) & (dates < end) & (frame["daysAhead"] == horizon)]
        if len(train) < 200 or test.empty:
            continue
        p = fit_predict(train, test, cols)
        preds.append(pd.DataFrame({"date": test["date"].values, "pred": p, "actual": test[TARGET].values}))
    return pd.concat(preds, ignore_index=True)


def summarise(res: pd.DataFrame) -> dict:
    hit = (np.sign(res["pred"]) == np.sign(res["actual"])).mean()
    up = (res["actual"] > 0).mean()
    corr = np.corrcoef(res["pred"], res["actual"])[0, 1]
    slope = np.polyfit(res["pred"], res["actual"], 1)[0]
    mae = (res["pred"] - res["actual"]).abs().mean()
    mae0 = res["actual"].abs().mean()
    return {"n": len(res), "hit%": 100 * hit, "alwaysUp%": 100 * max(up, 1 - up), "corr": corr,
            "slope": slope, "MAE": mae, "MAE_noChange": mae0}


def main() -> None:
    raw = pd.read_csv(CSV)
    raw = raw.rename(columns={raw.columns[0]: "date"})
    raw["date"] = pd.to_datetime(raw["date"])
    daily = raw.drop_duplicates("date").set_index("date").sort_index()
    cand = candidate_features(daily)
    frame = raw.merge(cand, left_on="date", right_index=True, how="left")
    missing = [c for c in BASE if c not in frame.columns]
    if missing:
        sys.exit(f"training_data.csv lacks expected columns: {missing}")
    print(f"{len(daily)} trading days, {frame['date'].min().date()} to {frame['date'].max().date()}; "
          f"test folds from {FIRST_TEST.date()} (quarterly, expanding window, {PURGE_DAYS}-day purge)\n")

    sets = {"current": BASE}
    for name, cols in GROUPS.items():
        sets[f"+ {name}"] = BASE + cols
    sets["+ all"] = BASE + [c for cols in GROUPS.values() for c in cols]

    for horizon in HORIZON_GROUPS:
        rows = []
        for name, cols in sets.items():
            rows.append({"features": name, **summarise(run(frame, cols, horizon))})
        table = pd.DataFrame(rows).set_index("features")
        print(f"=== {horizon}-day-ahead ===")
        print(table.round(3).to_string())
        print()


if __name__ == "__main__":
    main()
