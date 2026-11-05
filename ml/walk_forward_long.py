"""
Long-history walk-forward test (2005 onward) of candidate momentum features.

Companion to walk_forward.py, which only has three years of data. This one downloads GBP/USD and the
other major currencies from Frankfurter (public, no key) back to 2005, so the test covers the 2008 crisis,
the 2016 Brexit vote and the 2022 mini-budget. Macro inputs (US 2y yield, VIX, UK rates, Brent) need a
FRED key and are left out, so "current" here means the shipped model's price-derived features only;
the comparison between feature sets is what matters.

Expanding window, yearly folds from 2010, 14-day purge. Offline research only: touches nothing shipped.

    python ml/walk_forward_long.py
"""
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import requests

try:  # use the Windows certificate store (needed behind antivirus HTTPS inspection); verification stays on
    import truststore
    truststore.inject_into_ssl()
except ImportError:
    pass
from sklearn.ensemble import HistGradientBoostingRegressor

CACHE = Path(__file__).parent / "fx_history_2005.csv"
START_YEAR = 2005
FIRST_TEST_YEAR = 2010
PURGE_DAYS = 14
HORIZON_GROUPS = {1: [1, 2, 3], 5: [4, 5, 6, 7]}
SYMBOLS = ["USD", "EUR", "JPY", "CAD", "CHF"]
WINDOWS = {"2016 Brexit": ("2016-06-01", "2016-12-31"), "2022 mini-budget": ("2022-09-01", "2022-12-31"),
           "2008 crisis": ("2008-09-01", "2009-06-30")}


def fetch_history() -> pd.DataFrame:
    if CACHE.exists():
        return pd.read_csv(CACHE, index_col=0, parse_dates=True)
    rows = {}
    for year in range(START_YEAR, date.today().year + 1):
        r = requests.get(f"https://api.frankfurter.dev/v1/{year}-01-01..{year}-12-31",
                         params={"base": "GBP", "symbols": ",".join(SYMBOLS)}, timeout=60)
        r.raise_for_status()
        for d, rates in r.json()["rates"].items():
            rows[pd.Timestamp(d)] = rates
    df = pd.DataFrame.from_dict(rows, orient="index").sort_index()
    df.to_csv(CACHE)
    return df


def rsi14(p: pd.Series) -> pd.Series:
    d = p.diff()
    g, l = d.clip(lower=0).rolling(14).mean(), (-d.clip(upper=0)).rolling(14).mean()
    return 100 - 100 / (1 + g / l.replace(0, np.nan))


def build(gbp: pd.DataFrame) -> pd.DataFrame:
    p = gbp["USD"]
    ret = p.pct_change()
    f = pd.DataFrame(index=p.index)
    # current price-derived features (as in train_model.py)
    for n in (1, 5, 10, 20):
        f[f"ch{n}d"] = p.pct_change(n)
    f["rsi14"] = rsi14(p)
    f["vol10"] = ret.rolling(10).std()
    f["gbpEur5d"] = gbp["EUR"].pct_change(5)
    f["eurUsd5d"] = (gbp["USD"] / gbp["EUR"]).pct_change(5)
    # USD basket vs EUR, JPY, CAD, CHF: how many USD per unit of each, GBP excluded -> USD value is 1/(GBP->X) * GBP->USD
    legs = pd.DataFrame({s: (gbp["USD"] / gbp[s]).pct_change(5) for s in ["EUR", "JPY", "CAD", "CHF"]})
    f["usdBasket5d"] = -legs.mean(axis=1)  # USD per X rising = USD weaker
    legs1 = pd.DataFrame({s: (gbp["USD"] / gbp[s]).pct_change(1) for s in ["EUR", "JPY", "CAD", "CHF"]})
    f["usdBasket1d"] = -legs1.mean(axis=1)
    f["month"] = f.index.month
    f["dow"] = f.index.dayofweek
    # candidate groups
    vol60 = ret.rolling(60).std()
    f["volScaled5d"] = f["ch5d"] / f["vol10"]
    f["volScaled20d"] = f["ch20d"] / f["vol10"]
    prev5 = (1 + f["ch10d"]) / (1 + f["ch5d"]) - 1
    f["accel5d"] = f["ch5d"] - prev5
    f["slopeGap5v20"] = f["ch5d"] / 5 - f["ch20d"] / 20
    f["priceVsSma20"] = p / p.rolling(20).mean() - 1
    f["sma10VsSma50"] = p.rolling(10).mean() / p.rolling(50).mean() - 1
    f["change60d"] = p.pct_change(60)
    lo, hi = p.rolling(20).min(), p.rolling(20).max()
    f["rangePos20d"] = (p - lo) / (hi - lo)
    f["belowHigh20d"] = p / hi - 1
    f["volRatio10v60"] = f["vol10"] / vol60
    return f


BASE = ["ch1d", "ch5d", "ch10d", "ch20d", "rsi14", "vol10", "gbpEur5d", "eurUsd5d", "usdBasket5d", "usdBasket1d",
        "month", "dow"]
GROUPS = {
    "A volScaled": ["volScaled5d", "volScaled20d"],
    "B accel": ["accel5d", "slopeGap5v20"],
    "C maGap": ["priceVsSma20", "sma10VsSma50", "change60d"],
    "D range": ["rangePos20d", "belowHigh20d"],
    "E volRegime": ["volRatio10v60"],
}


def long_frame(feat: pd.DataFrame, p: pd.Series, horizons) -> pd.DataFrame:
    parts = []
    for h in horizons:
        d = feat.copy()
        d["daysAhead"] = h
        d["target"] = p.shift(-h) / p - 1
        d["date"] = d.index
        parts.append(d.dropna(subset=["target"]))
    return pd.concat(parts, ignore_index=True)


def run(frame: pd.DataFrame, cols: list[str], horizon: int) -> pd.DataFrame:
    group = HORIZON_GROUPS[horizon]
    out = []
    for year in range(FIRST_TEST_YEAR, frame["date"].max().year + 1):
        start, end = pd.Timestamp(f"{year}-01-01"), pd.Timestamp(f"{year + 1}-01-01")
        train = frame[(frame["date"] < start - pd.Timedelta(days=PURGE_DAYS)) & frame["daysAhead"].isin(group)]
        test = frame[(frame["date"] >= start) & (frame["date"] < end) & (frame["daysAhead"] == horizon)]
        if test.empty:
            continue
        cols_all = cols + ["daysAhead"]
        m = HistGradientBoostingRegressor(max_depth=4, max_iter=300, learning_rate=0.015, min_samples_leaf=40,
                                          l2_regularization=1.0, random_state=0)
        m.fit(train[cols_all], train["target"])
        out.append(pd.DataFrame({"date": test["date"].values, "pred": m.predict(test[cols_all]),
                                 "actual": test["target"].values}))
    return pd.concat(out, ignore_index=True)


def stats(r: pd.DataFrame) -> dict:
    hit = 100 * (np.sign(r["pred"]) == np.sign(r["actual"])).mean()
    up = 100 * (r["actual"] > 0).mean()
    return {"n": len(r), "hit%": hit, "alwaysUp%": max(up, 100 - up),
            "corr": np.corrcoef(r["pred"], r["actual"])[0, 1]}


def main() -> None:
    gbp = fetch_history()
    p = gbp["USD"]
    feat = build(gbp)
    frame = long_frame(feat, p, sorted({h for g in HORIZON_GROUPS.values() for h in g}))
    print(f"{len(gbp)} trading days {gbp.index.min().date()} to {gbp.index.max().date()}; "
          f"yearly folds {FIRST_TEST_YEAR}-{gbp.index.max().year}\n")
    sets = {"current (price-only)": BASE}
    sets.update({f"+ {k}": BASE + v for k, v in GROUPS.items()})
    sets["+ all"] = BASE + [c for v in GROUPS.values() for c in v]
    for h in HORIZON_GROUPS:
        rows, extra = [], {}
        for name, cols in sets.items():
            res = run(frame, cols, h)
            row = {"features": name, **stats(res)}
            for w, (a, b) in WINDOWS.items():
                sub = res[(res["date"] >= a) & (res["date"] <= b)]
                row[w + " hit%"] = 100 * (np.sign(sub["pred"]) == np.sign(sub["actual"])).mean()
            rows.append(row)
        print(f"=== {h}-day-ahead ===")
        print(pd.DataFrame(rows).set_index("features").round(3).to_string())
        print()


if __name__ == "__main__":
    main()
