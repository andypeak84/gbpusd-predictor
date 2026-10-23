# Model training (offline)

This is what replaces the app's old hand-tuned linear formula with a model actually
trained on data. It runs offline, not on the phone — the phone only loads the exported
`market_predictor_trees.json` file.

## Why Colab, not local

Training uses `ydf` (Google's decision-forests library) and needs specific,
mutually-compatible dependency versions that are genuinely finicky to get right locally
(we went through several rounds of pip conflicts figuring this out). **Use the provided
Colab notebook (`train_model_colab.ipynb`) rather than running `train_model.py`
directly** — it sidesteps all of that.

1. Go to [colab.research.google.com](https://colab.research.google.com) → File → Upload
   notebook → pick `train_model_colab.ipynb`.
2. Run the cells top to bottom:
   - Cell 1 installs `ydf`.
   - The upload cell pops a file picker — select your BoE `.xlsx` files (see below).
   - The key cell: paste your FRED API key directly as a string (get one free at
     [fred.stlouisfed.org](https://fred.stlouisfed.org/docs/api/api_key.html)). Don't
     paste it anywhere else (chats, commits, etc.) — treat it like a password.
   - The remaining cells build the dataset, train, self-verify the export, and let you
     download `market_predictor_trees.json`.
3. Copy that file into `app/src/main/assets/market_predictor_trees.json`.

`train_model.py` itself still exists and is what the notebook's core-logic cell is
generated from (so notebook and script never drift apart) — you can run it locally too
if you've got `ydf` working in your own environment, but Colab is the supported path.

## Multi-horizon (1-14 days)

One model serves every horizon from 1 to 14 days — `daysAhead` is itself a training
feature (see `HORIZONS` in `train_model.py`), so training generates 14 rows per
historical date (one per horizon) instead of one. At inference time, the app passes
whatever `daysAhead` the user picked into the same loaded model — see
`MarketRepository.featuresFrom()` and the horizon buttons in `MainActivity`. No
per-horizon retraining or separate model files needed.

## Why gbpUsd's raw price level isn't a feature

An earlier version fed the model both GBP/USD's raw price level and its relative
features (5-day change, RSI). That was a mistake: `gbpUsd` came out as the single most
important feature by a wide margin (importance ~1.75 vs ~0.98 for the next-highest), and
real-world testing confirmed it as the likely cause of persistently bearish predictions
-- RSI (the feature actually meant to detect overbought/oversold momentum) read a
genuinely neutral 51.1 on a day the model still called for heavy losses, ruling out
momentum as the driver.

The mechanism: across a 20-year training window, GBP/USD has spent time in most price
zones during very different market regimes -- the 2008 financial crisis, the 2016
Brexit vote, the 2022 mini-budget crisis, and calmer periods in between. A tree matching
"today's rate is near where it's been before" can easily learn "this level -> bad things
happened next" from a handful of those episodes, with no way to distinguish a genuine
present-day echo of a crisis from an ordinary day that happens to share a price level
with one. `gbpUsdChange5d` and `rsi14` carry the same kind of information -- is the rate
moving, and how much -- without that trap, since they're relative to *recent* history,
not fixed points learned from decades of very different conditions.

### Why the target is de-meaned per horizon

Making `daysAhead` a raw feature has a real failure mode: since the target is the
*actual* forward return, a tree minimizing error can (and, observed in practice, will)
learn "larger daysAhead → more negative expected return" if that's what the training
window's history happened to average out to — e.g. a 10-year window containing GBP/USD's
post-2016-Brexit-vote decline. The result is predictions that mechanically reproduce
that historical drift on every single row, regardless of current conditions — a
forecast that always points the same direction isn't a forecast.

The fix: before training, subtract each horizon's average return (computed from the
training split only, not test, to avoid leakage) from every example's target, forcing
the tree ensemble to learn genuine conditional deviations from a flat/zero-drift
baseline instead. There's no statistical basis for assuming a multi-year historical
sample mean predicts future average return for something this close to an efficient
market (Meese & Rogoff, 1983, and much replicated since). The per-horizon baseline is
stored separately in the exported JSON (`horizon_baseline`) and added back at inference
time (`TreeModelPredictor.kt`), so the final numbers are still real, usable rates — the
tree part just has to actually earn its contribution with real signal now.

## Why not TFLite

The original plan was to export a `.tflite` file. That turned out to be a dead end:
TF-DF/YDF's tree-inference ops are custom C++ kernels that were never built to work with
TFLite's converter — confirmed by an actual conversion failure, and independently by
Google's own AI developer forum, where someone asked this exact question and got "no,
this doesn't work" as the answer. It's not a version-pinning problem.

Since a gradient-boosted-trees model is fundamentally just a set of numeric if/else
comparisons, the fix was to skip TFLite entirely: `train_and_export()` walks the trained
model's tree structure directly (via `ydf`'s documented tree-inspection API) and writes
it out as plain JSON. The Android app (`TreeModelPredictor.kt`) walks that JSON with
`org.json`, which is built into Android — no ML runtime dependency at all, nothing that
can version-conflict.

**Self-verification, not just documentation:** the exact leaf-value format and whether a
separate "bias" term was needed weren't fully certain from `ydf`'s docs alone, so
`train_and_export()` doesn't just trust them — after exporting, it replays the JSON
through a pure-Python tree-walker and compares the result against the model's own
`.predict()` on real held-out rows. If they don't match (even after correcting for a
constant bias), it raises immediately with the actual numbers, rather than silently
shipping a broken model. If you ever see that error, paste it back — it means something
about the tree structure differs from what's documented.

## Feature names, not positions

The JSON is keyed by feature name (`"feature": "us2y"`, etc.), not column position.
`MarketRepository` builds a `Map<String, Double>` with every feature name the model
could reference; `TreeModelPredictor` looks values up by name as it walks. This means
retraining with a different feature set (e.g. adding `ukRate`) needs **no Kotlin
changes** as long as `MarketRepository` already computes that feature — which it does,
for all 11 possible features, regardless of which subset the model was actually trained
on.

## UK rate: how the live app actually gets it

The app tried a few approaches here, in order of how the current fallback chain
prioritizes them (see `MarketRepository.refreshData`):

1. **Manual entry** (`ManualUk2yStore`) — the true 2yr gilt yield, if you've entered one.
2. **`fetchGiltYieldFromGitHub`** — a daily-updated JSON hosted on the user's own GitHub
   (`andypeak84/gilt-yield-fetcher`), scraped from BoE's yield-curve archive. This is the
   real, live-fetchable source of the true 2yr gilt yield — **not** a static constant —
   so training on `ukRate`/`spread2y` does NOT create a train/serve mismatch, contrary to
   what earlier versions of this README said.
3. `IUDBEDR` (Bank Rate) as a direct BoE IADB fetch — a real, working series, but BoE's
   IADB endpoint blocks bulk historical pulls (the kind training needs) even though
   narrow "last 10 days" queries mostly get through, so it's not usable for training data
   directly.
4. `FIXED_UK_BANK_RATE` — a fixed constant, last resort only if 1-3 all fail AND there's
   no previous day's value to carry forward either (see `carriedForwardFields`).
5. `IUDVNP02` ("2yr nominal par yield") as an IADB series code — doesn't exist. BoE's
   fitted yield curve (where a true 2yr gilt yield comes from) has no API at all,
   confirmed on BoE's own site. This is why (2) exists at all — it's scraping the archive
   files linked below, not calling a nonexistent live endpoint.

Because (2) genuinely refreshes daily, `ukRate`/`spread2y` are safe to train on and are
in the shipped default model — see the model-selection notes below for why, and for the
one caveat (leakage on longer training windows) worth knowing about.

## Optional: real 2yr gilt yield from BoE's yield curve archive

BoE publishes the actual fitted 2yr gilt yield as part of downloadable data files (not
the blocked scripted query endpoint):

**Daily government liability curve (nominal): archive data**
`https://www.bankofengland.co.uk/-/media/boe/files/statistics/yield-curves/glcnominalddata.zip`

BoE splits the full history (back to 1979) across several era-covering `.xlsx` files.
The parser (`load_uk2y_series` in `train_model.py`) has been verified against a real
downloaded file — it reads the `3. spot, short end` sheet, finds the column where the
`months:` header row equals 24, and pulls the date-indexed 2yr yield out of it.

The notebook's dataset-building cell already passes `include_uk2y=True` and all
uploaded files, with `include_uk2y_levels = True` — the shipped default is the
11-feature version (`ukRate`, `spread2y`, `spread2yChange1d`, `spread2yChange5d`, plus
the base 7). Set `include_uk2y_levels = False` for the 9-feature version if retraining
on a longer window (see below for why that matters).

### 2025-08 model-selection notes

A retrain surfaced the same failure mode already documented above for `gbpUsd`:
`spread2y`'s raw level came out as the single most important feature by a clear margin
(7.45, vs. 5.54 for the next-highest) on a **10-year training window**, while holdout
directional accuracy on that run came out at 48.0% — *below* random chance. Testing the
exported model directly showed why: across a broad sweep of realistic "typical/moderate"
conditions, predictions stayed negative almost everywhere, flipping positive only at
extreme feature values. Same shape as the `gbpUsd` bug — a raw level, not a relative/
momentum feature, dominating importance — same likely mechanism: the UK-US rate spread
has probably revisited similar levels across very different historical regimes (2016
Brexit, 2020 covid, 2022 mini-budget), giving trees the same "this level → bad things
happened next" trap that `gbpUsd`'s price level had.

A systematic comparison across training-window length, `uk2y_levels` on/off, and
gradient-boosting hyperparameters (`num_trees`/`max_depth`/`shrinkage`/`min_examples`)
found:

| window | levels | hyperparams | holdout directional accuracy |
|---|---|---|---|
| 10yr | in | 300/6/0.05/5 (ydf's original hardcoded values) | 48.0% |
| 10yr | in | 150/6/0.05/5 | 50.0% |
| 10yr | in | 150/4/0.05/50 | 50.1% |
| 10yr | in | 150/4/0.03/50 | 47.5% |
| 10yr | in | 150/4/0.1/50 | 49.9% |
| 10yr | out | 300/6/0.05/5 | 49.8% |
| 3yr | in | 300/6/0.05/5 | 53.8% |
| 3yr | out | 300/6/0.05/5 | 55.7% |
| 3yr | out | 150/4/0.03/50 | 57.4% |
| **3yr** | **in** | **150/4/0.03/50** | **56.5%** (shipped) |

Two takeaways: (1) on the 10-year window, no hyperparameter combination tried reliably
beat chance (47.5-50.1% throughout) — the leakage is structural to what's *in* that much
history, not something a learner setting fixes. (2) On the 3-year window, regularization
(`max_depth=4`, `min_examples=50` in particular — they directly limit how narrowly a
tree can carve out a leaf matching one historical episode) suppressed `spread2y`'s
dominance almost as effectively as dropping it outright: with levels included,
`spread2y`'s importance flattened to near parity with the next feature (1.24 vs. 1.13,
down from a 1.35x lead), and holdout accuracy (56.5%) landed within a point of the
levels-excluded variant (57.4%) — noise, given a ~115-day holdout.

**Shipped: 3-year window, `include_uk2y_levels = True`, `num_trees=150, max_depth=4,
shrinkage=0.03, min_examples=50`.** Levels were kept in (rather than taking the
marginally-higher 57.4% levels-excluded number) because the live app genuinely fetches
`ukRate`/`spread2y` daily via `fetchGiltYieldFromGitHub` — no train/serve mismatch — so
there was no reason to throw away a real, available feature for a ~1pt difference within
noise.

**Not yet done:** stress-testing this exact config against the 2020 covid crash and 2022
mini-budget crisis specifically (`run_stress_tests` in the notebook/`train_model.py`
does this) — the 56.5-57.4% numbers above come from a chronological holdout that sits
inside the same calm-ish recent trend as its training data, so they say more about
"normal" conditions than about how this config behaves right as a regime breaks.

**BoE's terms of use** restrict this data to non-commercial use. Fine for personal use;
worth knowing if this app ever becomes something you'd charge for.

### 2026-08 missing-value fix — the numbers above are now stale baselines, not current

Every number in the table above was computed on training data that silently
forward-filled (`ffill`) gaps in `us2y`/`vix`/`oilPrice`/`ukRate` — a business day a
source didn't publish for got the prior day's value relabeled as today's, while
`gbpUsd` (and the label derived from it) stayed genuinely current. That's a real
timestamp-misalignment leak: some columns in a nominally-same-date row were quietly one
or more days stale, others weren't. `build_dataset()` no longer does this — a real gap
is now `np.nan`, and `ydf`/`TreeModelPredictor.kt` both already natively support
missing values via each split's own `missingGoesRight`/`condition.missing`, so this
needed no inference-side changes, only removing the fabrication upstream. (A related bug
in the same fix: `pandas.Series.pct_change()` defaults to `fill_method='pad'`, which
was silently re-forward-filling inside `gbpUsdChange5d`/`oilChange5d`/
`spread2yChange1d`/`spread2yChange5d` even after the explicit `.ffill()` calls were
removed — fixed by passing `fill_method=None` explicitly on all four.)

**First real run on the fixed pipeline, 3yr window / levels-in / 150-4-0.03-50 (same
shipped config as above):**

- **9,002 train rows, 1,589 test rows** (chronological split)
- **Holdout directional accuracy: 55.7%** — in line with the pre-fix 56.5%, so the fix
  didn't cost accuracy; treat this as the new baseline rather than expecting it to
  reproduce the old number exactly, since the underlying data changed.
- **Feature importances**: `ukRate` 1.39, `vix` 1.08, `spread2y` 1.05, `oilPrice` 0.78,
  `rsi14` 0.41, `daysAhead` 0.37, `oilChange5d` 0.33, `gbpUsdChange5d` 0.33, `us2y` 0.27,
  `spread2yChange5d` 0.16, `spread2yChange1d` 0.15. Healthier shape than the leakage
  signature above: no single raw level runs away with importance (`ukRate`→`vix` is a
  1.29x gap, vs. the 10-year window's 1.35x-1.75x single-level blowouts) — not proof
  the leak is fully gone, but the shape you'd hope to see.
- **Export verification**: 1,589/1,589 rows matched `model.predict()` exactly (median
  error 0.0, one expected float-rounding boundary case, harmless).

**Stress test, run for the first time against real numbers** (separate model, trained
on its own longer 2018-onward frame purely so 2020/2022 are in scope — NOT the shipped
model, which only ever sees its 3-year window; see the notebook's stress-test cell
comment):
- `covid_crash_2020`: 45.6% (below chance)
- `mini_budget_crisis_2022`: 36.4% (well below chance, over 910 test rows — not noise)

Read this as: the shipped model has never seen a genuine regime break in training (by
construction, given the rolling 3-year window), and this style of model — same
features/hyperparameters — handled the two real regime breaks in recent history worse
than a coin flip, `mini_budget_crisis_2022` notably so. That's a real, current
limitation, not a historical footnote from before the fix. Each stress figure pools all
14 horizons into one accuracy number, so it doesn't distinguish "bad at 1-day, fine by
day 10" from "uniformly bad" — worth breaking down by horizon before drawing a stronger
conclusion than "don't trust this model's direction through a genuine crisis."

## Retraining

There's no scheduled retraining — this is a manual, offline step. A sensible cadence is
quarterly, or whenever it feels stale.

## Known simplification

The model predicts a single point 14 trading days ahead (% change in GBP/USD), not a
full day-by-day path across the window. The app compares "now" vs. "predicted in 14
days" to suggest transfer-now vs. wait. It won't tell you "day 6 is the best day" within
the window — that would need a sequence model, a bigger step up in complexity. Worth
doing later if the simple version proves useful.


## Per-horizon-group models (v79)

`train_grouped.py` trains one ensemble per horizon group (1-3, 4-7, 8-14 days) on the frame `train_model.py` saved
as `training_data.csv`, and writes ONE `market_predictor_trees.json` with a `models` list (each entry:
`horizons`, `bias`, `trees`) plus merged `horizon_baseline` / `horizon_sd` / `horizon_mae`. Run `train_model.py` first
(it writes training_data.csv), then `python train_grouped.py --training-csv training_data.csv`, and copy the result to
`app/src/main/assets/`. ydf has no wheel for Python 3.14: the repo has been trained with an embeddable Python 3.12 +
ydf 0.8.0 (see the CHANGELOG v79 note for the measured results on two holdout windows).
