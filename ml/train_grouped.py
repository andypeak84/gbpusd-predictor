"""Trains one model PER HORIZON GROUP and exports them in a single market_predictor_trees.json.

Why: one pooled model fits all 14 horizons with the same tree depth/settings. Splitting into groups
(1-3, 4-7, 8-14 days) let each group's trees specialise, and on the 2026 holdouts it was equal or
better than the pooled model on both windows tested (see README.md, "Per-horizon-group models"). The
app (TreeModelPredictor) picks the group by the daysAhead it is asked for.

Usage (after train_model.py has written training_data.csv, which is the exact frame it trained on):

    python train_grouped.py --training-csv training_data.csv --output-dir <dir>

Output format: the same keys as the single-model file (feature_order, horizon_baseline, horizon_mae,
horizon_sd -- each keyed by daysAhead, merged across groups) plus "models": a list of
{"horizons": [1,2,3], "bias": ..., "trees": [...]}. Old single-model files (top-level "trees"/"bias")
still load: the app falls back to them when "models" is absent.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import tempfile
from pathlib import Path

import pandas as pd

import train_model as tm

GROUPS = [(1, 2, 3), (4, 5, 6, 7), (8, 9, 10, 11, 12, 13, 14)]
HP = dict(num_trees=300, max_depth=4, shrinkage=0.015, min_examples=50, l2_regularization=0.0)

# Inputs that publish a day after the ECB fix. With --lag-stale (the shipped setting since v87) each row gets these
# from the PREVIOUS trading date, matching what the app has at its 16:00 UTC refresh (today's ECB rate, yesterday's
# yields / VIX / Brent). Tested 2026-10-26: better than same-day on both holdout windows (62.7% vs 58.3% latest,
# 48.2% vs 45.1% earlier, all horizons).
STALE = ["ukRate", "spread2y", "spread2yChange1d", "spread2yChange5d", "spread2yChange10d", "us2y",
         "us2yChange5d", "vix", "vixChange5d", "brentChange5d"]


def lag_stale(frame: pd.DataFrame) -> pd.DataFrame:
    out = frame.copy()
    cols = [c for c in STALE if c in out.columns]
    for h in out["daysAhead"].unique():
        mask = out["daysAhead"] == h
        out.loc[mask, cols] = out.loc[mask, cols].shift(1).values
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--training-csv", type=Path, default=Path(__file__).parent / "training_data.csv")
    ap.add_argument("--output-dir", type=Path, default=Path(__file__).parent)
    ap.add_argument("--features-from", type=Path, default=None,
                    help="existing market_predictor_trees.json whose feature_order to reuse "
                         "(default: output-dir's, else the training script's FEATURE_ORDER)")
    ap.add_argument("--lag-stale", action="store_true",
                    help="take yields/VIX/Brent from the previous trading date (the app's v87+ live setup)")
    args = ap.parse_args()

    frame = pd.read_csv(args.training_csv, index_col=0, parse_dates=True).sort_index(kind="stable")
    if args.lag_stale:
        frame = lag_stale(frame)
        print("lag-stale: yields/VIX/Brent taken from the previous trading date")
    src = args.features_from or (args.output_dir / "market_predictor_trees.json")
    feats = json.load(open(src))["feature_order"] if src.exists() else list(tm.FEATURE_ORDER)

    merged = {"feature_order": feats, "horizon_baseline": {}, "horizon_mae": {}, "horizon_sd": {}, "models": []}
    for hs in GROUPS:
        sub = frame[frame["daysAhead"].isin(hs)]
        print(f"group {hs[0]}-{hs[-1]}: {len(sub)} rows")
        with tempfile.TemporaryDirectory() as td, contextlib.redirect_stdout(io.StringIO()):
            tm.train_and_export(sub, feats, Path(td), **HP)
            m = json.load(open(Path(td) / "market_predictor_trees.json"))
        for key in ("horizon_baseline", "horizon_mae", "horizon_sd"):
            for h in hs:
                merged[key][str(h)] = m[key][str(h)]
        merged["models"].append({"horizons": list(hs), "bias": m["bias"], "trees": m["trees"]})

    args.output_dir.mkdir(parents=True, exist_ok=True)
    out = args.output_dir / "market_predictor_trees.json"
    out.write_text(json.dumps(merged))
    print(f"wrote {out} ({sum(len(g['trees']) for g in merged['models'])} trees in {len(GROUPS)} groups)")


if __name__ == "__main__":
    main()
