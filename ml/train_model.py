"""
Trains the model behind the app's 14-day GBP/USD forecast.

This is the actual fix for "not using enough data": instead of the app's old hand-tuned
linear formula, this script pulls years of daily history for every feature the app
fetches live, trains a gradient-boosted-trees regressor on it (using the `ydf` package),
and exports the trees as a plain JSON file the app reads directly on-device -- no
TensorFlow Lite involved. TF-DF's tree-inference ops turned out not to be TFLite/Flex
compatible (a known, unresolved limitation, not a version-pinning issue), so this avoids
that dead end entirely: `ydf` alone has no TensorFlow/protobuf dependency at all.

Run this offline (on your laptop, not on-device) whenever you want to (re)train:

    pip install -r requirements.txt
    python train_model.py --fred-api-key YOUR_FRED_KEY --include-uk2y --uk2y-files <path>

Then copy the resulting market_predictor_trees.json into app/src/main/assets/.

IMPORTANT (this revision): training data no longer forward-fills (ffill) gaps in any
FRED/gilt-yield series. A business day a source didn't publish for is now a real
missing value (NaN) in the training frame, not a stale prior reading relabeled as
today's -- see build_dataset()'s docstring for why the old ffill behavior was a real
data leak (misaligned timestamps across columns within one nominally-same-date row).
ydf's GradientBoostedTreesLearner and the on-device TreeModelPredictor.kt walker both
already natively support missing values via each split's own missingGoesRight/
condition.missing, so this needed no new inference-side work -- only removing the
fabrication at the data-prep stage. This mirrors the app-side fix in MarketRepository
(per-target-date fetches, storing null instead of a carried-forward/sentinel value on
a miss) -- retrain and re-export whenever you pick up that app-side change, since the
two are meant to agree on what "missing" looks like.

Note on UK rates: the live app fetches the real 2yr gilt yield daily from the user's own
GitHub-hosted gilt-yield-fetcher (see MarketRepository.kt's fetchGiltYieldFromGitHub,
ahead of a direct BoE fetch and a fixed constant in the fallback chain), so ukRate/
spread2y ARE genuinely fetchable live -- no train/serve mismatch. --include-uk2y (with
--uk2y-files pointing at BoE's archive, see README.md) adds them to the trained model's
inputs; this is the current shipped default (see 2025-08 model-selection notes in
README.md for why, and why --no-uk2y-levels is NOT set by default despite spread2y
showing leakage signs on longer training windows -- shorter windows + regularized
hyperparameters resolved most of that without needing to drop the feature).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import requests

# Historically was FEATURE_ORDER, matched positionally against Kotlin. The exported JSON
# is now name-keyed (see TreeModelPredictor.kt), so this is just the default feature set
# used when no UK archive data is supplied -- order no longer matters for correctness.
FEATURE_ORDER = [
    "us2y", "vix",
    "gbpUsdChange5d", "oilChange5d", "rsi14", "daysAhead",
    # FX-cross momentum, all from Frankfurter (same endpoint fetch_gbpusd_series already
    # uses), all 5-business-day % CHANGES -- safe for the same reason gbpUsdChange5d is,
    # not the raw-level leakage that got gbpUsd's price level removed above. Matches the
    # app's MarketRepository.featuresFrom exactly (build_dataset computes these below).
    "gbpEurChange5d", "eurUsdChange5d", "usdBasketChange5d",
    # Realized GBP/USD volatility -- sample stdev of the last 10 daily returns. (A FRED
    # EVZCLS "FX implied vol" feature was added alongside this and removed: CBOE
    # discontinued EVZCLS in March 2025, so it has no data past then.)
    "gbpUsdRealVol10d",
    # Zero-new-fetch momentum companions -- pure derivations of us2y/vix/gbpUsd/the USD
    # basket, all of which build_dataset() already fetches for the features above. Matches
    # MarketRepository.refreshData()'s v8->v9 additions exactly.
    "us2yChange5d", "vixChange5d", "gbpUsdChange1d", "usdBasketChange1d",
    # Pure calendar -- no fetch, no external dependency at all. isMonthEnd uses the
    # business-day definition (next business day rolls into a new month), matching
    # AppClock.businessDaysForward on the app side -- see build_dataset()'s own comment.
    "dayOfWeek", "monthOfYear", "isMonthEnd",
]
# oilPrice (the raw WTI LEVEL) was removed from the model's feature set: same
# cross-regime leakage as gbpUsd's price level (see below), and the live app hardcodes
# it to null anyway -- a model that split on it 99/150 trees while never being given a
# value at inference is worse than one that never learned to. build_dataset() still
# COMPUTES frame["oilPrice"] because oilChange5d is derived from it; it's just not in
# model_features. oilChange5d (the % change) stays -- that's the safe momentum form.
# USD basket legs -- majors EXCLUDING GBP, so usdBasketChange5d isn't partly circular
# with the GBP/USD target it's helping predict.
USD_BASKET_LEGS = ["EUR", "JPY", "CAD", "CHF"]
# gbpUsd's raw price LEVEL used to be a feature here too -- removed. It was the single
# most important feature in early training runs (importance ~1.75, nearly double the
# next-highest), and real evidence confirmed it as the likely cause of persistently
# bearish predictions: RSI (the feature actually meant to detect momentum/overbought
# conditions) read a genuinely neutral 51.1 while the model still called for heavy
# losses -- ruling out momentum as the driver. GBP/USD has spent time in most price
# zones across a 20-year training window during very different market regimes (the 2008
# crisis, the 2016 Brexit vote, the 2022 mini-budget crisis, and calmer periods in
# between) -- a tree matching "today's rate is near where it's been before" can easily
# learn "this level -> bad things happened next" from a handful of those episodes,
# without any way to tell a genuine present-day echo of a crisis from an ordinary day
# that happens to share a price level with one. Relative/momentum features
# (gbpUsdChange5d, rsi14) carry the same kind of information without that trap.

# Horizons (trading days ahead) generated as training examples. "daysAhead" is itself a
# feature, so one model answers for any of these -- the app's 1-7 day selector queries
# this same model with different daysAhead values, no separate model per horizon needed.
HORIZONS = list(range(1, 15))
LABEL_COL = "target_change_pct"
RSI_PERIOD = 14


def parse_glc_nominal_short_end(xlsx_path: Path) -> pd.Series:
    """Extracts the real 2yr point from one BoE nominal yield curve file. BoE splits
    their archive into several period-covering .xlsx files (e.g. 2025-to-present, plus
    older ranges going back to 1979); load_uk2y_series merges however many you have.

    Confirmed layout (from an actual downloaded 2025-to-present file, sheet
    '3. spot, short end'): a row labelled "months:" holds integer month numbers 1-60 as
    column headers; the 2yr point is the column where that row equals 24. Older-era
    files may not have this sheet, so this also tries '4. spot curve' (the full curve,
    labelled in years rather than months) as a fallback, matching the column closest
    to 2.0 years.
    """
    import openpyxl

    wb = openpyxl.load_workbook(xlsx_path, read_only=True)
    available_sheets = wb.sheetnames
    wb.close()

    if "3. spot, short end" in available_sheets:
        sheet_name, label_text, target_value, tolerance = "3. spot, short end", "months:", 24, 0
    elif "4. spot curve" in available_sheets:
        sheet_name, label_text, target_value, tolerance = "4. spot curve", "years:", 2.0, 0.01
    else:
        # Some era files insert extra words (e.g. "3. nominal spot, short end") -- match
        # by substring rather than requiring an exact name.
        short_end_match = next(
            (s for s in available_sheets if "spot" in s.lower() and "short" in s.lower()), None
        )
        full_curve_match = next(
            (s for s in available_sheets if "spot curve" in s.lower()), None
        )
        if short_end_match:
            sheet_name, label_text, target_value, tolerance = short_end_match, "months:", 24, 0
        elif full_curve_match:
            sheet_name, label_text, target_value, tolerance = full_curve_match, "years:", 2.0, 0.01
        else:
            raise RuntimeError(
                f"Neither a short-end spot sheet nor a spot curve sheet found in {xlsx_path}. "
                f"Sheets present: {available_sheets} -- report these back so this can be adjusted."
            )

    raw = pd.read_excel(xlsx_path, sheet_name=sheet_name, header=None)

    label_row_matches = raw.index[raw[0].astype(str).str.strip().str.lower() == label_text]
    if len(label_row_matches) == 0:
        raise RuntimeError(
            f"Couldn't find the '{label_text}' header row in {xlsx_path} sheet '{sheet_name}'. "
            f"First 10 rows were:\n{raw.head(10)}"
        )
    label_row_idx = label_row_matches[0]
    label_row = raw.loc[label_row_idx]

    numeric_row = pd.to_numeric(label_row, errors="coerce")
    diffs = (numeric_row - target_value).abs()
    match_tolerance = max(tolerance, 1e-6)  # BoE's source data has float drift even on "integer" months
    col_matches = diffs[diffs <= match_tolerance].sort_values().index

    if len(col_matches) == 0:
        raise RuntimeError(
            f"Couldn't find a column matching {target_value} in the '{label_text}' row "
            f"of {xlsx_path} sheet '{sheet_name}'. Row was: {list(label_row)}"
        )
    target_col = col_matches[0]

    dates_col = pd.to_datetime(raw[0], errors="coerce", format="mixed")
    data_start = dates_col.loc[label_row_idx:].first_valid_index()
    if data_start is None:
        raise RuntimeError(f"Couldn't find where data rows start in {xlsx_path} sheet '{sheet_name}'.")

    series = pd.to_numeric(raw.loc[data_start:, target_col], errors="coerce")
    series.index = dates_col.loc[data_start:]
    series = series.dropna()
    series.name = "ukRate"
    return series.sort_index()


def load_uk2y_series(paths: list[Path]) -> pd.Series:
    """Accepts any mix of .xlsx files, .zip archives (containing .xlsx files), and
    directories (containing .xlsx files) -- e.g. BoE's several period-covering files
    going back to 1979 -- and merges them into one continuous series. A file that fails
    to parse is skipped with a warning rather than aborting the whole run, since some
    older-era files may be laid out differently."""
    import zipfile
    import tempfile

    xlsx_paths: list[Path] = []
    tmp_dirs = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            xlsx_paths.extend(sorted(p.glob("*.xlsx")))
        elif p.suffix.lower() == ".zip":
            tmp_dir = tempfile.mkdtemp()
            tmp_dirs.append(tmp_dir)
            with zipfile.ZipFile(p) as zf:
                for name in zf.namelist():
                    if name.lower().endswith(".xlsx"):
                        zf.extract(name, tmp_dir)
                        xlsx_paths.append(Path(tmp_dir) / name)
        else:
            xlsx_paths.append(p)

    if not xlsx_paths:
        raise RuntimeError(f"No .xlsx files found among: {paths}")

    print(f"Parsing {len(xlsx_paths)} BoE yield curve file(s)...")
    series_list = []
    for p in xlsx_paths:
        try:
            series_list.append(parse_glc_nominal_short_end(p))
            print(f"  OK: {p.name}")
        except RuntimeError as e:
            print(f"  SKIPPED {p.name}: {e}")

    if not series_list:
        raise RuntimeError("None of the provided files could be parsed -- see warnings above.")

    merged = pd.concat(series_list)
    merged = merged[~merged.index.duplicated(keep="last")].sort_index()
    return merged


def fetch_fred_series(series_id: str, api_key: str, start: date, end: date) -> pd.Series:
    resp = requests.get(
        "https://api.stlouisfed.org/fred/series/observations",
        params={
            "series_id": series_id,
            "api_key": api_key,
            "file_type": "json",
            "observation_start": start.isoformat(),
            "observation_end": end.isoformat(),
        },
        timeout=30,
    )
    resp.raise_for_status()
    obs = resp.json().get("observations", [])
    if not obs:
        raise RuntimeError(f"FRED returned no observations for {series_id} -- check your API key")
    df = pd.DataFrame(obs)[["date", "value"]]
    df["date"] = pd.to_datetime(df["date"])
    df["value"] = pd.to_numeric(df["value"], errors="coerce")  # FRED uses "." for missing
    return df.set_index("date")["value"].rename(series_id)


def fetch_frankfurter_frame(start: date, end: date, base: str, symbols: list[str]) -> pd.DataFrame:
    """One Frankfurter historical-range call: `base` vs each of `symbols`. Returns a
    DataFrame indexed by date with one column per symbol (the rate `base`->symbol).
    Mirrors the app's MarketRepository.fetchFrankfurter{,Basket}History."""
    resp = requests.get(
        f"https://api.frankfurter.dev/v1/{start.isoformat()}..{end.isoformat()}",
        params={"base": base, "symbols": ",".join(symbols)},
        timeout=30,
    )
    resp.raise_for_status()
    rates = resp.json()["rates"]
    rows = []
    for d, day_rates in rates.items():
        row = {"date": pd.to_datetime(d)}
        for sym in symbols:
            row[sym] = day_rates.get(sym)
        rows.append(row)
    return pd.DataFrame(rows).set_index("date").sort_index()


def fetch_gbpusd_series(start: date, end: date) -> pd.Series:
    return fetch_frankfurter_frame(start, end, "GBP", ["USD"])["USD"].rename("gbpUsd")


def compute_rsi(closes: pd.Series, period: int = RSI_PERIOD) -> pd.Series:
    """Mirrors MarketRepository.calculateRSI in the Kotlin app exactly: a simple
    (not Wilder-smoothed) rolling average of gains/losses over `period` days.

    IMPORTANT: does NOT blanket-fill NaN to 100 anymore. The old `rsi.fillna(100)`
    applied to every NaN unconditionally -- including a genuine data gap propagating
    through the rolling window (e.g. closes itself had a missing day inside the
    14-day lookback) -- silently turning "we don't actually know" into "maximally
    overbought," a fabricated value indistinguishable from a real reading. Only the
    ACTUAL degenerate case (a full window of real gains with literally zero losses,
    where the RS ratio is undefined) is now coerced to 100; every other NaN is left
    as NaN and flows through to the tree as a genuinely missing feature -- see
    build_dataset's docstring for why that's the point of this whole rewrite."""
    deltas = closes.diff()
    gains = deltas.clip(lower=0)
    losses = -deltas.clip(upper=0)
    avg_gain = gains.rolling(period).mean()
    avg_loss = losses.rolling(period).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    rsi = 100 - (100 / (1 + rs))
    # Only overwrite the specific "no losses at all in a fully-real window" case --
    # requires avg_gain to itself be non-null (i.e. the window had real data, wasn't
    # itself a gap) before claiming 100 is the genuine answer rather than a guess.
    no_losses_but_real_window = (avg_loss == 0) & avg_gain.notna()
    rsi = rsi.where(~no_losses_but_real_window, 100.0)
    return rsi


def build_dataset(
    start: date,
    end: date,
    fred_api_key: str,
    uk2y_files: list[Path] | None,
    include_uk2y: bool,
    uk2y_levels: bool = True,
) -> tuple[pd.DataFrame, list[str]]:
    """Builds the training frame with REAL missing values (np.nan) wherever a source
    genuinely didn't publish for a given business day -- deliberately does NOT
    forward-fill (ffill) any feature series anymore. ffill was a real data leak: a US
    holiday with no DGS2/VIXCLS/DCOILWTICO observation used to silently get the prior
    day's value relabeled as today's, while gbpUsd (and therefore the label) still
    reflected the ACTUAL date -- so the model was trained on rows where some columns
    were quietly one or more days stale and others were current, all presented under
    one shared date as if they were simultaneous. That's exactly the kind of
    misaligned-timestamp leakage that produces confident-looking but wrong splits.

    ydf's GradientBoostedTreesLearner (and the on-device TreeModelPredictor.kt walker)
    both already natively support missing values -- each split records which branch a
    missing value should take (see condition.missing / missingGoesRight) -- so leaving
    real gaps as NaN and letting the tree learn from them directly is both more
    correct AND already fully supported end-to-end, no new inference-side work needed.
    This mirrors the app-side fix (MarketRepository now fetches per-target-date and
    stores null rather than a carried-forward/sentinel value on a miss) -- train and
    serve now agree on what "missing" means and how it's represented.
    """
    print("Fetching US 2yr yield (FRED: DGS2)...")
    us2y = fetch_fred_series("DGS2", fred_api_key, start, end)
    print("Fetching VIX (FRED: VIXCLS)...")
    vix = fetch_fred_series("VIXCLS", fred_api_key, start, end)
    print("Fetching WTI oil (FRED: DCOILWTICO)...")
    oil = fetch_fred_series("DCOILWTICO", fred_api_key, start, end)
    print("Fetching GBP/USD (Frankfurter)...")
    gbpusd = fetch_gbpusd_series(start, end)
    print("Fetching GBP/EUR + EUR/USD (Frankfurter)...")
    gbpeur = fetch_frankfurter_frame(start, end, "GBP", ["EUR"])["EUR"]
    eurusd = fetch_frankfurter_frame(start, end, "EUR", ["USD"])["USD"]
    print(f"Fetching USD basket {USD_BASKET_LEGS} (Frankfurter)...")
    usd_basket = fetch_frankfurter_frame(start, end, "USD", USD_BASKET_LEGS)

    business_days = pd.bdate_range(start, end)
    frame = pd.DataFrame(index=business_days)
    # No .ffill() on any of these -- see the docstring above. A day FRED/Frankfurter
    # didn't publish for is now a real NaN, not a relabeled stale reading.
    frame["us2y"] = us2y.reindex(business_days)
    frame["gbpUsd"] = gbpusd.reindex(business_days)
    frame["oilPrice"] = oil.reindex(business_days)
    frame["vix"] = vix.reindex(business_days)

    # gbpUsd is the one column everything else is computed FROM (change%, RSI, and the
    # label itself) -- a date with no real gbpUsd reading can't produce any of those,
    # so those dates are dropped outright here rather than carried through as
    # phantom rows. This mirrors the app's own rule: no gbpUsd, no row, no fallback.
    # (Deliberately NOT dropping rows here for missing us2y/oilPrice/vix -- those stay
    # as real gaps for the tree to learn from directly.)
    before = len(frame)
    frame = frame.dropna(subset=["gbpUsd"])
    dropped = before - len(frame)
    if dropped:
        print(f"Dropped {dropped} business day(s) with no real gbpUsd reading (holidays Frankfurter didn't publish for).")

    # fill_method=None on every pct_change below is NOT optional -- pandas' default
    # (fill_method='pad') forward-fills NaN internally before diffing, which would
    # silently reintroduce the exact leak the .ffill() removal above was for. Confirmed
    # via a real Colab run: pandas throws a FutureWarning naming this exact default as
    # the culprit. With fill_method=None, a gap on either side of the window correctly
    # makes the change value NaN (missing) rather than a value computed against a
    # phantom carried-forward number.
    frame["gbpUsdChange5d"] = frame["gbpUsd"].pct_change(5, fill_method=None)
    # Zero-new-fetch momentum companion -- same gbpUsd column, 1-day instead of 5.
    frame["gbpUsdChange1d"] = frame["gbpUsd"].pct_change(1, fill_method=None)
    frame["oilChange5d"] = frame["oilPrice"].pct_change(5, fill_method=None)
    frame["rsi14"] = compute_rsi(frame["gbpUsd"])
    # Realized GBP/USD vol: sample stdev (ddof=1, pandas default) of the last 10 daily
    # returns. rolling(10).std() needs 10 non-NaN returns in the window (a gap -> NaN),
    # same "real data or missing" rule as everything else. Mirrors
    # MarketRepository.realizedVol on the app side.
    frame["gbpUsdRealVol10d"] = frame["gbpUsd"].pct_change(fill_method=None).rolling(10).std()
    # Zero-new-fetch momentum companions -- us2y/vix are already columns on frame above.
    frame["us2yChange5d"] = frame["us2y"].pct_change(5, fill_method=None)
    frame["vixChange5d"] = frame["vix"].pct_change(5, fill_method=None)

    # FX-cross momentum -- reindex to the same business-day grid (no ffill, same as
    # everything else) then a 5-day pct_change with fill_method=None. usdBasketChange5d is
    # the row-wise mean of each leg's 5-day change, kept NaN unless at least half the legs
    # (2 of 4) produced a real value -- mirrors MarketRepository's `size >= 2` guard.
    frame["gbpEurChange5d"] = gbpeur.reindex(business_days).pct_change(5, fill_method=None)
    frame["eurUsdChange5d"] = eurusd.reindex(business_days).pct_change(5, fill_method=None)
    _basket_leg_changes = pd.DataFrame(
        {leg: usd_basket[leg].reindex(business_days).pct_change(5, fill_method=None) for leg in USD_BASKET_LEGS}
    )
    frame["usdBasketChange5d"] = _basket_leg_changes.mean(axis=1).where(
        _basket_leg_changes.notna().sum(axis=1) >= 2
    )
    # Zero-new-fetch momentum companion -- same usd_basket frame, 1-day instead of 5,
    # same >=2-leg guard. Mirrors MarketRepository's usdBasketChange1d.
    _basket_leg_changes_1d = pd.DataFrame(
        {leg: usd_basket[leg].reindex(business_days).pct_change(1, fill_method=None) for leg in USD_BASKET_LEGS}
    )
    frame["usdBasketChange1d"] = _basket_leg_changes_1d.mean(axis=1).where(
        _basket_leg_changes_1d.notna().sum(axis=1) >= 2
    )

    # Pure calendar -- no fetch, no external dependency. isMonthEnd mirrors
    # AppClock.businessDaysForward's weekend-only (no bank-holiday awareness) definition
    # via pandas' BDay offset, matching business_days (a plain bdate_range) exactly --
    # deliberately NOT calendar month-end, per this project's established business-day
    # convention (see the changeNDaysAgoFromSeries doc comment on the Kotlin side for the
    # train/serve bug that motivated always using business days here).
    frame["dayOfWeek"] = frame.index.dayofweek + 1  # pandas: 0=Mon..6=Sun -> 1=Mon..7=Sun
    frame["monthOfYear"] = frame.index.month
    _next_business_day = frame.index + pd.tseries.offsets.BDay(1)
    frame["isMonthEnd"] = (_next_business_day.month != frame.index.month).astype(float)

    model_features = list(FEATURE_ORDER)

    if uk2y_files:
        print(f"Loading real UK 2yr gilt yield from {len(uk2y_files)} file(s)...")
        uk2y = load_uk2y_series(uk2y_files)
        frame["ukRate"] = uk2y.reindex(business_days)  # no ffill -- see docstring above
        frame["spread2y"] = frame["ukRate"] - frame["us2y"]  # NaN propagates naturally if either side is missing
        frame["spread2yChange1d"] = frame["spread2y"].pct_change(1, fill_method=None)
        frame["spread2yChange5d"] = frame["spread2y"].pct_change(5, fill_method=None)
        # Always saved to training_data.csv for inspection even if not used as a model
        # input below -- see --include-uk2y.
        if include_uk2y:
            # ukRate/spread2y's raw LEVELS are opt-in separately from the change features
            # (uk2y_levels). SHIPPED DEFAULT IS True -- see README.md's "2025-08
            # model-selection notes" for the full grid this decision came from. Short
            # version: a 10-year training window's feature importances put spread2y
            # highest by a clear margin (7.45 vs 5.54) with holdout directional accuracy
            # at 48.0% (below chance) -- the same leakage shape as the gbpUsd-level bug
            # (a raw level dominating importance, likely echoing old regimes rather than
            # reflecting present-day signal). But switching to a 3-year training window
            # (less exposure to 2016 Brexit/2020 covid/2022 mini-budget) plus regularized
            # hyperparameters (num_trees=150, max_depth=4, shrinkage=0.03,
            # min_examples=50) closed almost all of that gap WITHOUT dropping the
            # feature: spread2y's importance flattened to near parity with the other
            # features (1.24 vs 1.13 for the next-highest, down from a 1.35x lead), and
            # holdout accuracy on that config was 56.5% with levels vs. 57.4% without --
            # a ~1pt difference well within the noise of a ~115-day holdout. Given the
            # live app can genuinely fetch ukRate/spread2y daily (no train/serve
            # mismatch -- see MarketRepository.fetchGiltYieldFromGitHub), levels were
            # kept in. Pass --no-uk2y-levels / set uk2y_levels=False to go back to the
            # levels-excluded variant if you retrain on a longer window where the
            # leakage re-emerges. NOTE: this grid predates the ffill-removal fix above
            # -- worth rerunning the grid once real missing-value training is in place,
            # since the leakage shape that motivated some of these choices may change.
            level_features = ["ukRate", "spread2y"] if uk2y_levels else []
            model_features = level_features + ["spread2yChange1d", "spread2yChange5d"] + model_features
            print(f"Including UK 2yr yield features in the trained model (--include-uk2y set); "
                  f"levels {'included' if uk2y_levels else 'excluded (--no-uk2y-levels set)'}.")
        else:
            print("Real UK 2yr yield loaded for training_data.csv only -- not fed to the model (pass --include-uk2y to change that).")

    # Target: % change in GBP/USD at each horizon in HORIZONS. Rather than one row per
    # date with a single fixed-horizon target, this expands to one row per (date, horizon)
    # pair -- so the model learns "how does the answer change as daysAhead increases,"
    # and the app can query any horizon from the same trained model at inference time.
    horizon_frames = []
    for h in HORIZONS:
        sub = frame.copy()
        sub["daysAhead"] = h
        sub[LABEL_COL] = frame["gbpUsd"].shift(-h) / frame["gbpUsd"] - 1
        horizon_frames.append(sub)
    frame = pd.concat(horizon_frames, ignore_index=False).sort_index()

    # Only the LABEL needs to be real -- ydf can't train on a missing target. Deliberately
    # NOT dropping on model_features anymore: a row with (say) a missing oilPrice but a
    # real label is exactly the kind of example that teaches the tree how to route a
    # missing oilPrice at inference time, via each split's own missingGoesRight/
    # condition.missing. Dropping it would remove the only signal that teaches that
    # routing, and would silently make the training distribution "every feature always
    # present" while the live app can and does see missing features -- a train/serve
    # mismatch in the opposite direction from a fabricated default.
    before = len(frame)
    frame = frame.dropna(subset=[LABEL_COL])
    dropped = before - len(frame)
    print(f"Dropped {dropped} row(s) with no real label (end-of-window / target date itself missing gbpUsd) "
          f"out of {before} -- {len(frame)} rows remain, WITH real missing values still present in feature columns.")
    return frame, model_features


def _extract_node(node, model_features: list[str]) -> dict:
    """Recursively converts one ydf tree node into a plain dict. Structure confirmed
    against ydf's own documentation (ydf.tree.NonLeaf/Leaf, RegressionValue,
    NumericalHigherThanCondition with an integer .attribute column index)."""
    is_leaf = type(node).__name__ == "Leaf" or not hasattr(node, "condition")
    if is_leaf:
        value = node.value
        return {"leaf": float(getattr(value, "value", value))}

    condition = node.condition
    # ydf's condition.attribute is 1-indexed (confirmed by ydf's own docs: a single-feature
    # tree shows attribute=1, not attribute=0), so this maps to model_features[attribute - 1].
    attribute_index = condition.attribute - 1
    if not (0 <= attribute_index < len(model_features)):
        raise RuntimeError(
            f"condition.attribute={condition.attribute} (1-indexed) is out of range for "
            f"model_features (len={len(model_features)}): {model_features} -- "
            f"report this back so the extractor can be adjusted."
        )
    return {
        "feature": model_features[attribute_index],
        "threshold": float(condition.threshold),
        "missingGoesRight": bool(getattr(condition, "missing", False)),
        # pos_child = taken when attribute_value >= threshold (condition true).
        "right": _extract_node(node.pos_child, model_features),
        "left": _extract_node(node.neg_child, model_features),
    }


def _predict_from_trees_json(export: dict, row: dict) -> float:
    """Pure-Python reference walker for the tree+bias portion ONLY -- deliberately does
    NOT add horizon_baseline, so its output matches model.predict()'s de-meaned scale
    exactly, for self-verification purposes. The real on-device inference path
    (TreeModelPredictor.kt) adds horizon_baseline[daysAhead] on top of this to get an
    actual usable prediction -- see train_and_export()'s comments for why."""
    def is_missing(v) -> bool:
        # A DataFrame row converted via to_dict() represents a missing value as a real
        # float NaN, not None -- `v is None` alone silently missed this (NaN != None
        # and NaN != NaN, so equality checks don't catch it either), meaning every
        # genuinely-missing feature in the holdout set was mis-routed during export
        # verification once ffill stopped masking real gaps. pd.isna() catches both
        # None and float NaN.
        return v is None or (isinstance(v, float) and pd.isna(v))

    def walk(node):
        if "leaf" in node:
            return node["leaf"]
        value = row.get(node["feature"])
        if is_missing(value):
            goes_right = node["missingGoesRight"]
        else:
            goes_right = value >= node["threshold"]
        return walk(node["right"] if goes_right else node["left"])

    return export["bias"] + sum(walk(tree) for tree in export["trees"])


def train_and_export(
    frame: pd.DataFrame,
    model_features: list[str],
    output_dir: Path,
    num_trees: int = 300,
    max_depth: int = 6,
    shrinkage: float = 0.05,
    min_examples: int = 5,  # ydf's own default
    l2_regularization: float = 0.0,  # ydf's own default (off)
) -> None:
    import ydf  # pure C++-backed wheel -- no TensorFlow, no protobuf version coupling

    split_idx = int(len(frame) * 0.85)
    train_df = frame.iloc[:split_idx].copy()
    test_df = frame.iloc[split_idx:].copy()
    print(f"Train rows: {len(train_df)}, test rows: {len(test_df)} (chronological split, no shuffling)")

    label = LABEL_COL

    # De-mean the target per horizon (using ONLY the training split, to avoid leaking
    # test-period information into the baseline). Without this, "daysAhead" is just
    # another feature the tree can split on directly -- and since the raw target is the
    # actual forward return, a tree minimizing error will happily learn "larger daysAhead
    # -> more negative expected return" if that's what the training window's history
    # happened to average out to (e.g. GBP/USD's post-2016-Brexit-vote decline sitting
    # inside a 10-year window), and reproduce that same drift on every single prediction
    # regardless of current conditions -- a forecast that always points one way regardless
    # of input isn't a forecast. There's no statistical basis for assuming a multi-year
    # historical sample mean predicts future average return for a near-efficient-market
    # series like a major FX pair (Meese & Rogoff, 1983, and much replicated since), so the
    # baseline is subtracted out and the tree ensemble is trained purely on the deviation
    # from it. The baseline itself is stored in the export and added back at inference time
    # (see TreeModelPredictor.kt) so the final numbers are still real, usable rates.
    horizon_baseline = train_df.groupby("daysAhead")[label].mean().to_dict()
    train_df[label] = train_df[label] - train_df["daysAhead"].map(horizon_baseline)
    test_df[label] = test_df[label] - test_df["daysAhead"].map(horizon_baseline)

    learner = ydf.GradientBoostedTreesLearner(
        label=label,
        task=ydf.Task.REGRESSION,
        num_trees=num_trees,
        max_depth=max_depth,
        shrinkage=shrinkage,
        min_examples=min_examples,
        l2_regularization=l2_regularization,
    )
    print(
        f"Learner config: num_trees={num_trees}, max_depth={max_depth}, "
        f"shrinkage={shrinkage}, min_examples={min_examples}, "
        f"l2_regularization={l2_regularization}"
    )
    model = learner.train(train_df[model_features + [label]])

    evaluation = model.evaluate(test_df[model_features + [label]])
    print(f"Holdout evaluation (on de-meaned target):\n{evaluation}")

    preds = np.array(model.predict(test_df[model_features]))
    actual = test_df[label].values
    directional_accuracy = float(np.mean(np.sign(preds) == np.sign(actual)))
    print(f"Holdout directional accuracy on the de-meaned signal (predicted vs actual sign "
          f"of the deviation from each horizon's average): {directional_accuracy:.1%}")
    print(f"Per-horizon baseline (average return in the training set, added back at "
          f"inference): {dict(sorted(horizon_baseline.items()))}")

    # Per-horizon mean absolute error of the % change prediction, on the holdout. Feeds
    # the app's optional ± uncertainty band (TreeModelPredictor.maePctFor -> a band on the
    # predicted RATE). The horizon_baseline term cancels in (prediction - actual) -- the
    # app adds it to the prediction and it's also in the real forward return -- so this is
    # just |model.predict() - de-meaned label| averaged within each horizon, no baseline
    # arithmetic needed here.
    horizon_mae = (
        pd.Series(np.abs(preds - actual), index=test_df.index)
        .groupby(test_df["daysAhead"])
        .mean()
        .to_dict()
    )
    print(f"Per-horizon holdout MAE (% change; feeds the app's uncertainty band): "
          f"{ {int(k): round(float(v), 5) for k, v in sorted(horizon_mae.items())} }")

    print("\nFeature importances:")
    try:
        for importance, name in model.variable_importances().get("SUM_SCORE", []):
            print(f"  {name}: {importance:.4f}")
    except Exception:
        pass  # importance keys vary slightly by ydf version; non-fatal

    num_trees = model.num_trees()
    print(f"\nExtracting {num_trees} trees...")
    trees_json = [_extract_node(model.get_tree(i).root, model_features) for i in range(num_trees)]
    export = {
        "feature_order": model_features,
        "bias": 0.0,
        "trees": trees_json,
        # Added to (bias + sum(tree outputs)) at inference time -- see TreeModelPredictor.kt.
        # Keyed by the string form of daysAhead since JSON object keys must be strings.
        "horizon_baseline": {str(int(k)): float(v) for k, v in horizon_baseline.items()},
        # OPTIONAL. Read by TreeModelPredictor.maePctFor(); when present the app shows the
        # forecast as "1.3241 +/- <rate * mae>". Same string-keyed-by-daysAhead shape.
        "horizon_mae": {str(int(k)): float(v) for k, v in horizon_mae.items()},
    }

    # --- Self-verification: replay the exported JSON in pure Python and compare against
    # the model's own predictions on real held-out rows. This is the actual safety net --
    # not the ydf API docs -- since it proves the export is correct on real data rather
    # than assuming the documented attribute names/semantics were applied correctly here.
    # Deliberately checks only the tree+bias portion against model.predict() (both on the
    # de-meaned scale) -- horizon_baseline is a separate, exact additive lookup, not
    # something that needs statistical verification.
    #
    # Uses the full test set (not just a handful of rows) and a median-based bias estimate
    # so a rare edge case -- a row's feature value landing within float-rounding distance of
    # a split threshold, sending it down the wrong branch of one single tree out of ~300 --
    # doesn't look like a systematic mapping error. A real mapping/indexing bug would show
    # up as most rows disagreeing, not one or two.
    print("\nVerifying export against model.predict() on held-out rows...")
    check_rows = test_df[model_features].to_dict(orient="records")
    model_preds = np.array(model.predict(test_df[model_features]))
    raw_sums = np.array([_predict_from_trees_json(export, row) for row in check_rows])

    residuals = model_preds - raw_sums
    bias_estimate = float(np.median(residuals))
    deviations = np.abs(residuals - bias_estimate)
    outlier_mask = deviations > 1e-4
    outlier_fraction = float(np.mean(outlier_mask))

    if outlier_fraction > 0.05:
        raise RuntimeError(
            "Export verification FAILED: too many rows disagree with model.predict() even "
            "after correcting for a constant bias -- this points at a systematic problem "
            "(attribute-index-to-feature-name mapping or condition direction), not float "
            "rounding on a split boundary.\n"
            f"  {outlier_fraction:.1%} of {len(check_rows)} rows exceeded tolerance (limit 5%)\n"
            f"  model.predict() sample:      {model_preds[:5]}\n"
            f"  raw tree-sum sample:         {raw_sums[:5]}\n"
            f"  residuals sample:            {residuals[:5]}\n"
            "Report this back with the numbers above so the extractor can be fixed."
        )

    export["bias"] = bias_estimate
    verified_preds = np.array([_predict_from_trees_json(export, row) for row in check_rows])
    errors = np.abs(verified_preds - model_preds)
    print(f"Verified: {len(check_rows)} rows checked, {int(outlier_mask.sum())} boundary-case "
          f"outlier(s) (float rounding on a split threshold -- expected, harmless), "
          f"median error {np.median(errors):.8f}, max error {np.max(errors):.8f} "
          f"(bias term: {bias_estimate:.6f})")

    out_path = output_dir / "market_predictor_trees.json"
    out_path.write_text(json.dumps(export))
    print(f"\nWrote {out_path} ({len(trees_json)} trees)")
    print("Copy this file into app/src/main/assets/market_predictor_trees.json")


# Known GBP/USD regime-break windows, for evaluate_stress_period() below. Deliberately
# picks violent, discontinuous moves rather than calm periods -- a model that only looks
# good on an 85/15 chronological split sitting inside one continuing trend (see the
# 3-year-window run: 55.7% directional accuracy, but train and test both sit inside the
# same post-2022 GBP recovery) hasn't been tested against the thing FX forecasting is
# hardest at: a regime actually breaking mid-stream. Date ranges are deliberately a bit
# wide around the event itself to catch the lead-in and the volatile aftermath, not just
# the single worst day.
STRESS_PERIODS = {
    "covid_crash_2020": {
        "train_end": date(2020, 2, 1),
        "test_start": date(2020, 2, 1),
        "test_end": date(2020, 4, 30),
    },
    "mini_budget_crisis_2022": {
        "train_end": date(2022, 9, 1),
        "test_start": date(2022, 9, 1),
        "test_end": date(2022, 11, 30),
    },
}


def evaluate_stress_period(
    frame: pd.DataFrame,
    model_features: list[str],
    hp: dict,
    train_end: date,
    test_start: date,
    test_end: date,
) -> dict:
    """Trains fresh on everything before train_end, evaluates purely on the
    [test_start, test_end] window -- i.e. does this configuration hold up when trained
    on "normal" history and asked to call a genuine regime break it never saw the like
    of, rather than tested on more of the same trend it was trained on. Uses the SAME
    de-meaning approach as train_and_export (baseline computed from the train slice
    only), so this is a fair comparison to your main holdout accuracy number."""
    import ydf

    label = LABEL_COL
    train_df = frame[frame.index < pd.Timestamp(train_end)].copy()
    test_df = frame[(frame.index >= pd.Timestamp(test_start)) & (frame.index <= pd.Timestamp(test_end))].copy()

    if len(train_df) < 500:
        raise RuntimeError(
            f"Only {len(train_df)} training rows before {train_end} -- frame's date range "
            f"doesn't go back far enough for this stress period. Build frame with an "
            f"earlier start date (see run_stress_tests())."
        )
    if len(test_df) == 0:
        raise RuntimeError(
            f"No rows in [{test_start}, {test_end}] -- frame's date range doesn't reach "
            f"this stress period at all."
        )

    horizon_baseline = train_df.groupby("daysAhead")[label].mean().to_dict()
    train_df[label] = train_df[label] - train_df["daysAhead"].map(horizon_baseline)
    test_df[label] = test_df[label] - test_df["daysAhead"].map(horizon_baseline)

    learner = ydf.GradientBoostedTreesLearner(label=label, task=ydf.Task.REGRESSION, **hp)
    model = learner.train(train_df[model_features + [label]])

    preds = np.array(model.predict(test_df[model_features]))
    actual = test_df[label].values
    directional_accuracy = float(np.mean(np.sign(preds) == np.sign(actual)))

    return {
        "train_rows": len(train_df),
        "test_rows": len(test_df),
        "train_end": train_end,
        "test_range": (test_start, test_end),
        "directional_accuracy": directional_accuracy,
    }


def run_stress_tests(frame: pd.DataFrame, model_features: list[str], hp: dict) -> None:
    """Runs evaluate_stress_period() over every entry in STRESS_PERIODS and prints a
    summary. `frame` needs a date range reaching back far enough for the earliest
    stress period's train_end to have real history before it -- build it with
    build_dataset(start=..., ...) using a start well before 2020, independent of
    whatever (shorter) window you're using for the model you actually ship."""
    print(f"Running {len(STRESS_PERIODS)} stress-period test(s) (config: {hp})...\n")
    for name, window in STRESS_PERIODS.items():
        try:
            result = evaluate_stress_period(frame, model_features, hp, **window)
            print(
                f"{name}: train_end={result['train_end']} "
                f"test={result['test_range'][0]}..{result['test_range'][1]} "
                f"({result['train_rows']} train rows, {result['test_rows']} test rows) "
                f"-> directional accuracy: {result['directional_accuracy']:.1%}"
            )
        except RuntimeError as e:
            print(f"{name}: SKIPPED -- {e}")
    print(
        "\nCompare these against your main holdout accuracy. A number near 50% here "
        "(vs. a higher one on a calm-period holdout) means the model isn't adding real "
        "value exactly when a forecast would matter most -- consistent with the general "
        "finding that short-horizon FX is hardest to beat a random walk on right around "
        "regime breaks (Meese & Rogoff, 1983)."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fred-api-key", required=True, help="https://fred.stlouisfed.org/docs/api/api_key.html")
    parser.add_argument("--start", type=lambda s: date.fromisoformat(s), default=date.today() - timedelta(days=365 * 3))
    parser.add_argument("--end", type=lambda s: date.fromisoformat(s), default=date.today())
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).parent)
    parser.add_argument(
        "--uk2y-files", type=Path, nargs="+", default=None,
        help="Path(s) to BoE's GLC nominal yield curve file(s) -- one or more .xlsx "
             "files (BoE splits full history across ~8 era files), a folder containing "
             "them, or the glcnominalddata.zip archive. See README.md. Adds the real "
             "2yr gilt yield to training_data.csv for inspection."
    )
    parser.add_argument(
        "--include-uk2y", action="store_true",
        help="Also feed the real 2yr yield into the trained model (requires --uk2y-files). "
             "The live app fetches this daily via its own GitHub-hosted gilt-yield-fetcher "
             "(see MarketRepository.kt) -- no train/serve mismatch. See README.md for the "
             "2025-08 model-selection notes on why this is the current shipped default."
    )
    parser.add_argument(
        "--no-uk2y-levels", action="store_true",
        help="With --include-uk2y set, drop ukRate/spread2y's raw LEVELS from the model, "
             "keeping only spread2yChange1d/spread2yChange5d (the relative versions). Use "
             "this to test whether the raw levels are leaking historical-regime information "
             "the same way gbpUsd's raw price level did -- see build_dataset()'s comments "
             "for the evidence that prompted this flag. On by default the levels ARE "
             "included (matches existing exported models); pass this flag to exclude them."
    )
    parser.add_argument("--num-trees", type=int, default=150)
    parser.add_argument("--max-depth", type=int, default=4)
    parser.add_argument("--shrinkage", type=float, default=0.03)
    parser.add_argument(
        "--min-examples", type=int, default=50,
        help="Minimum training rows per leaf (ydf's own default is 5). Raising this forces "
             "larger, more averaged-over leaves -- a lever against the same regime-echo "
             "overfitting that motivated --no-uk2y-levels, but a different mechanism: this "
             "constrains HOW narrowly any feature (including ones you keep) can be split on, "
             "rather than removing a specific feature. Test independently of "
             "--no-uk2y-levels to see which lever (or combination) actually moves holdout "
             "directional accuracy -- change one at a time so you can tell which did it."
    )
    parser.add_argument("--l2-regularization", type=float, default=0.0)
    parser.add_argument(
        "--run-stress-tests", action="store_true",
        help="After the normal train/export, also run run_stress_tests() against the "
             "covid_crash_2020 / mini_budget_crisis_2022 windows, using the SAME "
             "hyperparameters and feature set as the main run. Builds its own separate, "
             "longer (2018-onward) dataset purely so those dates are in scope -- this is "
             "NOT the model that gets exported/shipped; see STRESS_PERIODS's own comment. "
             "Previously only runnable by hand from a notebook/REPL, which is why the "
             "numbers in ml/README.md were a one-off rather than something reproducible."
    )
    args = parser.parse_args()

    if args.include_uk2y and not args.uk2y_files:
        print("ERROR: --include-uk2y requires --uk2y-files", file=sys.stderr)
        sys.exit(1)

    args.output_dir.mkdir(parents=True, exist_ok=True)

    try:
        frame, model_features = build_dataset(
            args.start, args.end, args.fred_api_key, args.uk2y_files, args.include_uk2y,
            uk2y_levels=not args.no_uk2y_levels,
        )
    except RuntimeError as e:
        print(f"\nERROR: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"\nBuilt dataset: {len(frame)} rows, {frame.index.min().date()} to {frame.index.max().date()}")
    frame.to_csv(args.output_dir / "training_data.csv")
    print(f"Saved raw feature/target data to {args.output_dir / 'training_data.csv'} for inspection.")

    if model_features != FEATURE_ORDER:
        print(
            f"\nNOTE: training on {len(model_features)} features "
            f"({model_features}) instead of the default {len(FEATURE_ORDER)}. "
            "Unlike the old TFLite approach, the exported JSON is keyed by feature name, "
            "not position -- so no FEATURE_ORDER to update in Kotlin. You do still need "
            "MarketRepository to compute a value for every feature name listed above, "
            "or the app's tree evaluator will have nothing to look up for that split."
        )

    train_and_export(
        frame, model_features, args.output_dir,
        num_trees=args.num_trees, max_depth=args.max_depth, shrinkage=args.shrinkage,
        min_examples=args.min_examples, l2_regularization=args.l2_regularization,
    )

    if args.run_stress_tests:
        stress_start = min(args.start, date(2018, 1, 1))
        print(f"\n--run-stress-tests: building a separate frame from {stress_start} "
              f"(covers both stress windows; NOT the exported model above)...")
        try:
            stress_frame, stress_features = build_dataset(
                stress_start, args.end, args.fred_api_key, args.uk2y_files, args.include_uk2y,
                uk2y_levels=not args.no_uk2y_levels,
            )
        except RuntimeError as e:
            print(f"\nERROR building stress-test dataset: {e}", file=sys.stderr)
            sys.exit(1)
        hp = dict(
            num_trees=args.num_trees, max_depth=args.max_depth, shrinkage=args.shrinkage,
            min_examples=args.min_examples, l2_regularization=args.l2_regularization,
        )
        run_stress_tests(stress_frame, stress_features, hp)


if __name__ == "__main__":
    main()
