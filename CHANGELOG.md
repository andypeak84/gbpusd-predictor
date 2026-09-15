# Changelog

This project is now under git version control (`E:\claudes-space\gbp project\newgdp-v62`,
initial commit 2026-09-15). This file plus the inline comment at each change site remain
the record of what changed and why.

## v63 — 7 new features + retrain — 2026-09-15

Two kinds of new model input, both zero-new-fetch or zero-fetch entirely, plus the
retrain that activates them (and `gbpUsdRealVol10d`/`horizon_mae`, both already coded
but unused until now). Also fixes the training environment itself: `ydf>=0.9` has no
Windows wheels at all (dropped after 0.8.0) and 0.8.0 tops out at Python 3.12, so an
isolated Python 3.12 + `ydf==0.8.0` environment was used for this run instead of the
system's Python 3.14; `ml/requirements.txt`'s `ydf>=0.9` pin still targets Colab/Linux
where current versions install fine.

### Added — 4 momentum companions (zero new network calls)
`repository/MarketRepository.kt`, `ml/train_model.py`

Pure derivations of history already fetched every refresh for `us2y`/`vix`/`gbpUsd`/the
USD basket — reuse the existing `changeNDaysAgoFromSeries` call sites, no new fetch:
`us2yChange5d`, `vixChange5d` (5-business-day % changes), `gbpUsdChange1d`,
`usdBasketChange1d` (1-day companions to the existing 5d versions).

### Added — 3 pure-calendar features (no fetch at all)
Same files. `dayOfWeek` (1=Mon..5=Fri), `monthOfYear` (1-12), `isMonthEnd` (1.0 if the
next **business** day rolls into a new calendar month — deliberately business-day-based
via `AppClock.businessDaysForward`/pandas `BDay(1)`, not calendar month-end, matching
this project's established business-day convention rather than reintroducing the
calendar-vs-business-day train/serve bug fixed in the v63 review pass above).

### Files touched
- `data/MarketData.kt` — 7 nullable `Double?` fields; DB `version 8 → 9`.
- `repository/MarketRepository.kt` — computed in `refreshData()`, added to `featuresFrom()`.
- `MainActivity.kt` / `HistoryActivity.kt` — 7 lines / 7 columns, existing pattern.
- `ml/train_model.py` — 7 new columns in `build_dataset()`, appended to `FEATURE_ORDER`;
  new `--run-stress-tests` flag wires the previously-manual-only `run_stress_tests()`
  into `main()`, building its own 2018-onward frame so the numbers below are reproducible
  with one command instead of a one-off notebook run.
- `ml/train_model_colab.ipynb` — core-logic cell re-synced (byte-identical to the script
  minus `main()`, per this project's convention).

### Retrained and shipped — real numbers, not estimates
Config unchanged from the existing shipped default: 3-year window, `num_trees=150
max_depth=4 shrinkage=0.03 min_examples=50`, `--include-uk2y` with levels included.
21 features fed in (17 prior + these 7; `oilPrice`'s raw level stays excluded).

- **10,577 rows** (2023-09-18 to 2026-09-11), **8,990 train / 1,587 test** (chronological).
- **Holdout directional accuracy: 55.4%** — flat vs. the pre-this-change baseline
  (55.7%), i.e. the 7 new features didn't cost accuracy.
- **Export self-verification: 1,587/1,587 rows matched `model.predict()` exactly**
  (median error 0.0, 0 boundary-case outliers).
- **Feature importances (top 10 of 19 with nonzero SUM_SCORE):** `ukRate` 1.145,
  `gbpUsdRealVol10d` 0.718, `spread2y` 0.679, `vixChange5d` 0.615, `gbpEurChange5d`
  0.603, `us2yChange5d` 0.455, `rsi14` 0.333, `spread2yChange5d` 0.297,
  `usdBasketChange5d` 0.296, `gbpUsdChange5d` 0.272. `monthOfYear` (0.229) also shows
  real importance; `dayOfWeek` and `isMonthEnd` got zero splits this run (not an error —
  the tree ensemble just didn't find them useful this time; they stay in the feature set
  for future retrains to reconsider). No single feature dominates the way `gbpUsd`'s or
  `spread2y`'s raw levels once did (the project's established leakage smell) — healthy
  shape, consistent with accuracy holding steady rather than dropping.
- **`horizon_mae` now populated** — the ± uncertainty band goes live on this model
  (previously-shipped model had no `horizon_mae` block at all).

### Stress test — first run on the CURRENT pipeline, via the new `--run-stress-tests` flag
Separate model, same hyperparameters, trained on a 2018-onward frame purely so both
events are in scope (per `STRESS_PERIODS`'s own design — NOT the model shipped above):

- **`covid_crash_2020`: 46.3%** directional accuracy (7,448 train rows, 868 test rows,
  train_end 2020-02-01, test 2020-02-01..2020-04-30) — still below chance, essentially
  unchanged from the stale pre-fix figure (45.6%). Read as an honest, current limitation,
  not a historical footnote: this model style still doesn't call the covid crash better
  than a coin flip.
- **`mini_budget_crisis_2022`: 52.7%** directional accuracy (16,744 train rows, 910 test
  rows, train_end 2022-09-01, test 2022-09-01..2022-11-30) — a real improvement over the
  stale pre-fix figure (36.4%, well below chance), now just above chance. Likely reflects
  the missing-value-leak fix and/or the FX-cross features added since that stale number
  was recorded, not this round's 7 features specifically (no ablation run to isolate
  which change gets the credit — worth doing if this matters to you).

Both figures pool all 14 horizons into one number, so neither distinguishes "bad at
1-day, fine by day 10" from "uniformly bad" — per-horizon breakdown would need further
work, same caveat `ml/README.md` already noted.

## Pending v63 — realised-vol feature — 2026-09-06

One new feature: **`gbpUsdRealVol10d`** — sample stdev (ddof 1) of the last 10 daily
GBP/USD returns, computed on-device from the Frankfurter history already fetched for
`gbpUsd` / `rsi14`. **No API call.** Realised (as opposed to implied) volatility.

Files:
- `data/MarketData.kt` — 1 nullable `Double?` field; DB `version 7 → 8`.
- `repository/MarketRepository.kt` — new `realizedVol()` helper (matches pandas `.std()`
  ddof 1); `REALIZED_VOL_WINDOW = 10`; computed in `refreshData()` reusing the
  `pricesUpToTarget` list RSI already builds; added to `featuresFrom()`.
- `MainActivity.kt` — one line in "Features behind this prediction".
- `HistoryActivity.kt` — one column (`Realised vol 10d`).
- `ml/train_model.py` — `frame["gbpUsdRealVol10d"] = gbpUsd.pct_change(fill_method=None).rolling(10).std()`;
  added to `FEATURE_ORDER`.
- `ml/train_model_colab.ipynb` — core cell re-synced.

**Dropped before it shipped: `evz` (FRED `EVZCLS`).** Added as an FX implied-vol proxy,
then removed the same day — an API check showed CBOE **discontinued `EVZCLS` in March
2025** (last observation 2025-03-11), so it would have been null on every row from then
on. All `evz` fetch/field/feature/UI/train code was backed out; `gbpUsdRealVol10d` is the
whole of the volatility work. No free live FX implied-vol series exists on FRED anymore
(`OVXCLS`/`GVZCLS`/`VIXCLS`/`VXVCLS` are still live, just not FX).

**Inert until you retrain** — the app emits it, the shipped model doesn't reference it.

---

## v63 — housekeeping — 2026-09-06

- `app/build.gradle` — `versionCode 62 → 63`, `versionName "62" → "63"`.
- `ml/train_model.py` — `"oilPrice"` removed from `FEATURE_ORDER` (always-missing raw
  level, cross-regime leakage). `build_dataset()` still computes `frame["oilPrice"]`
  because `oilChange5d` derives from it; it's just no longer a model feature.
  `oilChange5d` stays.
- `ml/train_model_colab.ipynb` — core cell re-synced to the script again.

---

## Pending v63 — FX-cross features — 2026-09-06

Three new model features, all from Frankfurter (free, no API key, same-day FX data, no
publish lag). All are 5-business-day % **changes**, never raw levels, so they stay on the
safe side of the leakage line the project drew for `gbpUsd` / `spread2y` price levels.

**They do nothing until the model is retrained** — the app emits them now, but the shipped
`market_predictor_trees.json` was trained without them, and `TreeModelPredictor` looks up
features by name, so an unknown key is simply never referenced. A retrain that adds them
finds the app already supplying them.

| Feature | What it is |
|---|---|
| `gbpEurChange5d` | GBP/EUR 5-day % change — sterling's own strength with the shared USD leg removed |
| `eurUsdChange5d` | EUR/USD 5-day % change — the deepest FX pair, GBP/USD's main co-mover |
| `usdBasketChange5d` | mean 5-day % change of USD vs EUR/JPY/CAD/CHF (GBP deliberately excluded so it isn't circular with the target) — "is this a dollar-wide move?" |

Files:
- `data/MarketData.kt` — 3 nullable `Double?` fields (defaulted `null`, so old backups
  still deserialize); DB `version 6 → 7` in `AppDatabase.kt` (destructive migration was
  already configured — the table is a rolling cache the worker rebuilds in a day or two).
- `repository/MarketRepository.kt` — `fetchFrankfurterHistory` generalised to take
  `base`/`symbol` (defaults GBP/USD, existing call unchanged); new
  `fetchFrankfurterBasketHistory` for the multi-symbol basket call; three fetches +
  `changeNDaysAgoFromSeries` in `refreshData()`; the basket is the mean of whichever legs
  produced a change, `null` unless ≥ 2 of 4. Added to `featuresFrom()`.
- `MainActivity.kt` — three lines in "Features behind this prediction".
- `HistoryActivity.kt` — three columns (`GBP/EUR 5d`, `EUR/USD 5d`, `USD basket 5d`).
- `ml/train_model.py` — `fetch_gbpusd_series` generalised to `fetch_frankfurter_frame`;
  `build_dataset()` fetches the crosses + basket and computes the same three features the
  same way (`pct_change(5, fill_method=None)`, basket = row-mean with a ≥ 2-leg guard);
  added to `FEATURE_ORDER`. So the next Colab run trains on them with no further edits.
- `ml/train_model_colab.ipynb` — core-logic cell (cell 7) regenerated from the updated
  script; now byte-identical to `train_model.py` minus `main()`. Cells 9/11/14 unchanged
  (they use the `model_features` list `build_dataset` returns, so they pick the new
  features up automatically).

Bootstrap-fetch-log sources for the new calls: `frankfurter_GBPEUR_history`,
`frankfurter_EURUSD_history`, `frankfurter_USDbasket_history` (with a `PARTIAL` outcome
when some but not all basket legs matched the target date).

### Added — `horizon_mae` export (turns on the uncertainty band)
`ml/train_model.py`

- `train_and_export()` now computes per-horizon holdout MAE of the % change prediction
  (`|model.predict() − de-meaned label|` averaged within each `daysAhead`; the
  `horizon_baseline` term cancels in prediction − actual) and writes it to the export as
  a `"horizon_mae"` object, same string-keyed-by-daysAhead shape as `horizon_baseline`.
- `TreeModelPredictor.maePctFor()` already reads it, so the home-screen band
  (`1.3241 ± 0.0060`) switches on automatically the first time you drop in a model
  regenerated from this script. No app change needed.

### Verified, no change needed — new features through the export path
- `model_features` is name-keyed end to end: `learner.train(df[model_features])` →
  `_extract_node` maps ydf's 1-indexed `condition.attribute` back through the *same*
  list → `feature_order` in the JSON → `TreeModelPredictor` looks up by name. Adding
  three names to the tail of `FEATURE_ORDER` needs nothing else; `_extract_node`'s
  range check would catch a mismatch, and `_predict_from_trees_json` /
  `dropna(subset=[LABEL_COL])` handle the new NaN-bearing columns exactly as they
  already handle `oilPrice` / `us2y`.

### `oilPrice` — done
Removed from `FEATURE_ORDER` (see the v63 housekeeping entry at the top).

---

## Pending v63 — "finished" pass — 2026-09-05

Five features toward calling the app done.

### Added — forecast accuracy screen
`AccuracyActivity.kt` (new), `repository/PredictionScorer.kt` (new),
`AndroidManifest.xml`, `MainActivity.kt` (new "Accuracy" button)

- The app stored every prediction but never checked one. `PredictionScorer` walks
  every past `market_data` row, projects its stored 1-business-day forecast onto the
  target trading day (`AppClock.businessDaysForward`), finds the actual GBP/USD then
  (exact date, or nearest within 2 days), and returns predicted-vs-actual per row
  plus a summary: directional hit-rate, mean & median absolute rate error, and a
  count still pending.
- New "Accuracy" screen (button on the home screen next to History / Bootstrap Log)
  renders that as a table + summary line, with a caveat that small samples and calm
  markets flatter the number.
- Pure read logic — one `getAllEntries()` call, no writes, no new schema.

### Added — uncertainty band on the forecast
`ml/TreeModelPredictor.kt`, `MainActivity.kt`

- `TreeModelPredictor` now also reads an OPTIONAL `horizon_mae` object from the model
  JSON (same shape/handling as the existing `horizon_baseline`) and exposes
  `maePctFor(daysAhead)`.
- When present, the home-screen forecast shows `1.3241  ± 0.0060` instead of a bare
  point estimate (single- and multi-horizon).
- The **currently shipped `market_predictor_trees.json` has no `horizon_mae` block**,
  so nothing shows yet — this is plumbing. Regenerate the model with a `horizon_mae`
  block (per-horizon holdout MAE of the % change) and the band turns on with no
  further code change.

### Added — daily forecast notification
`work/MarketDataWorker.kt`, `GbpUsdPredictorApp.kt`, `AndroidManifest.xml`,
`MainActivity.kt`

- After a `SUCCESS` refresh, `MarketDataWorker` posts one notification with the day's
  call ("Wait: … may rise to ~X by <date>" / "Convert now: …" / "broadly stable"),
  tapping it opens the app. Fixed notification id, so each day replaces the last.
- Notification channel `daily_forecast` created in `GbpUsdPredictorApp.onCreate()`
  (`IMPORTANCE_DEFAULT`).
- `POST_NOTIFICATIONS` added to the manifest; `MainActivity` requests it once at
  launch on API 33+ (fire-and-forget — the notification path no-ops if denied).
- Only fires on `SUCCESS` (a genuinely new row), never on `SKIPPED` / `FAILED`.
- Uses `R.mipmap.ic_launcher` as the small icon — functional but not a proper
  monochrome notification icon; worth replacing with a dedicated white-on-transparent
  asset later.

### Added — staleness banner
`MainActivity.kt`, `repository/MarketRepository.kt` (constant made public),
`utils/AppClock.kt` (`businessDaysBetween`)

- If the newest stored row is 3+ **business** days behind where the daily refresh
  should be by now (`AppClock.today()` − `TARGET_LAG_BUSINESS_DAYS`), the home screen
  shows a full-width white-on-red banner with the heartbeat detail. Catches a stalled
  worker chain / multi-day outage that the small heartbeat line was too quiet for.
- Business-day comparison so a normal weekend never trips it.
- `MarketRepository.TARGET_LAG_BUSINESS_DAYS` changed from `private` to public for
  this.

### Added — amount input
`MainActivity.kt`

- Optional "Amount to convert (£)" field on the home screen, persisted in
  `SharedPreferences`. When set, the single-horizon forecast also shows it in dollars:
  `£1,000.00 → $1,324.10 now vs. $1,326.80 on <date>  (+2.70)`.
- "Apply" with an empty/invalid box clears it. Re-renders the forecast in place.

### New / changed helpers
- `AppClock.businessDaysForward(from, days)` and `AppClock.businessDaysBetween(a, b)`.
- `MainActivity`: `addBanner()`, `stalenessBanner()`, `addAmountInput()`,
  `maybeRequestNotificationPermission()`, `MainActivity.Companion` (prefs keys).

---

## Pending v63 — review pass — 2026-09-05

Review pass over the v62 source. Four fixes + one perf change + one new UI line,
across three Kotlin files.

### Fixed

#### Oil price fetch was completely broken — `oilChange5d` was always null
`app/src/main/java/com/example/gbpusdpredictor/network/OilPriceService.kt`

- The `@GET` URL was missing the branch segment
  (`.../andypeak84/Oilfetcher/data/oil-price.json`), which returns HTTP 404. A 404
  is a `retrofit2.HttpException`, not an `IOException`, so `retryNetwork()` never
  retried it — `fetchOilHistoryFromGitHub()` caught it, logged ERROR, and returned
  an empty map on every run since v62.
- Now a relative path with the branch: `andypeak84/Oilfetcher/main/data/oil-price.json`
  (verified 200 against the live repo; its default branch is `main`).
- Rewrote the stale "PLACEHOLDER PATH — update this" class KDoc.
- Effect: once FRED publishes the target window, `oilChange5d` populates for the
  first time since v62.

#### `retryNetwork()` ignored transient HTTP errors
`app/src/main/java/com/example/gbpusdpredictor/repository/MarketRepository.kt`

- Only `IOException` was retried. Added `isTransientHttp()`; retries now also fire
  on `retrofit2.HttpException` with status 429 / 502 / 503 / 504.
- Non-transient 4xx and parse errors still fail on the first attempt.
  `CancellationException` is re-thrown immediately, so coroutine cancellation is
  preserved.

#### All-null rows were written permanently and never healed
`MarketRepository.kt` — `refreshData()`

- If `gbpUsd`, `us2y`, `ukRate` **and** `vix` all came back null (e.g. a DNS blip
  at 21:00 UTC hitting every call at once), the row was still inserted and
  `SUCCESS` returned. The `getRowsForDate()` dedup check then treated that trading
  date as permanently captured — never re-fetched, even after the sources caught
  up.
- Now: `corePresent == 0` → no row written, `RefreshResult.FAILED` returned so
  `MarketDataWorker` retries on its backoff. `RefreshResult` enum doc updated.
- Trade-off: a genuine no-trading-data day (a shared US/UK bank holiday) will
  exhaust the 8 retries and leave **no row at all**, rather than a row full of
  "—". That's the intended, honest outcome.

#### "Change" features used a different lookback than the training script
`MarketRepository.kt` — `changeNDaysAgoFromSeries()`

- The app stepped back N **calendar** days; `ml/train_model.py` computes the same
  features via `pandas.pct_change(N)` on a business-day-reindexed series — N
  **business** days. For a 5-day change targeting a Wednesday: prior Wednesday in
  training vs. ~prior Friday served live. Affected `gbpUsdChange5d`,
  `oilChange5d`, `spread2yChange1d`, `spread2yChange5d`.
- Now steps back business days via `AppClock.businessDaysBack()`, matching the
  training definition. `toleranceDays` unchanged (still absorbs a holiday the
  business-day target itself lands on).
- Same function: the reference-point search now excludes `latestDate` itself, so a
  gap at the real reference date no longer produces a spurious exact `0.0` change
  (most likely to have bitten `spread2yChange1d`, daysAgo=1 / tolerance=1).

### Changed

#### Model JSON no longer parsed on the main thread
`app/src/main/java/com/example/gbpusdpredictor/MainActivity.kt`

- `treeModel` was constructed in `onCreate()`, parsing the ~120 KB / 150-tree JSON
  on the UI thread at every launch.
- Now `TreeModelPredictor?`, built lazily inside the existing
  `withContext(Dispatchers.IO)` block in `loadAndRender()` (first render only, then
  kept for the activity's life). `renderPrediction()` null-checks it; if it's not
  ready the existing "tree model not loaded" message shows.

### Added

#### "Next scheduled refresh" countdown on the home screen
`MainActivity.kt`

- New line under the existing "Last refresh attempt" heartbeat line:
  `Next scheduled refresh: in Xh Ym (21:00 UTC daily)`.
- Computed from `MarketDataScheduler.millisUntilNextUtc()` — the same function that
  schedules the job, so the countdown can't drift out of sync with the real
  schedule. It's the schedule **anchor**, not a live prediction: Doze/battery
  deferral or a FAILED run's retry backoff can move the actual next attempt.
  Meant to be read alongside the heartbeat line, not instead of it.

## Not done — needs an offline retrain (yours to run)

### `oilPrice` train/serve skew
- The shipped `app/src/main/assets/market_predictor_trees.json` splits on
  `oilPrice` (raw level) in **99 of 150 trees**, but the app hardcodes
  `oilPrice = null` in `refreshData()` and never puts it in the feature map —
  every one of those splits takes its trained "missing" branch on every
  prediction, every day.
- Fix: remove `"oilPrice"` from the feature list at `ml/train_model.py:60`,
  regenerate the JSON in Colab, copy it into `app/src/main/assets/`. Consistent
  with `ml/README.md`'s own "raw levels are leakage-prone" argument for dropping
  `gbpUsd`.
- Keep `oilChange5d` — it's a momentum feature (the "safe" kind) and is genuinely
  served now that the URL is fixed.
