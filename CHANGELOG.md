# Changelog

This project is now under git version control (`E:\claudes-space\gbp project\newgdp-v62`,
initial commit 2026-09-15). This file plus the inline comment at each change site remain
the record of what changed and why.

## v83 — Brent source credited as OilPriceAPI — 2026-10-23

Diagnostics sources and the Play listing now name OilPriceAPI (https://www.oilpriceapi.com/) as the Brent source, with the
EIA series as the fallback. `data/brent-latest.json` in Oilfetcher is rebuilt from OilPriceAPI's own history (no EIA values).

## v82 — remove WTI and calendar rows from the screens — 2026-10-23

The main-screen "Features behind this prediction" list and the History table no longer show Oil (WTI) 5d, day of week,
month or month-end (the v80 model does not use them); Diagnostics sources now lists Brent only (EIA, FRED, OilPriceAPI).
The stored columns remain (null for new rows), so old rows and export/import are unaffected.

## v81 — fix: GitHub-hosted data parsed to nothing in the release build — 2026-10-23

The data fetch log showed EMPTY (0 observations) for us2y, vix, brent, oil and the UK 2yr gilt on every release-build refresh,
so those features (and their 5-day changes) were null in the stored rows. The five series were parsed with
`gson.fromJson(el, PrivateNestedClass::class.java)`, which returned nothing under R8; they are now read from the JSON tree
by hand (no reflection). Also logs the start of the response when a series parses to zero observations.

## v80 — WTI and calendar features removed — 2026-10-23

- **WTI is gone.** Its EIA/FRED series runs 2-3 days stale; Brent (which has the fresher daily recorder) carries the oil
  signal. The model no longer has `oilChange5d`, and the app stops fetching the WTI file (the table column stays, null).
- **Calendar features removed:** `dayOfWeek`, `monthOfYear`, `isMonthEnd` (3 years of data = each month seen 3 times, so
  `monthOfYear` mostly memorised a calendar). 21 features, same per-horizon-group design and settings as v79.
- Measured on the same two holdouts as v79 (direction / error): latest window 58.3% / 0.640% (v79: 61.5% / 0.629%),
  earlier window 45.1% / 0.899% (v79: 42.6% / 0.921%). Removing WTI cost about 2 points on the latest window, mainly in the
  4-7 day group; the owner chose to drop it anyway because the data is stale.

## v79 — retrained model, one model per horizon group — 2026-10-23

- **Retrained** on data to 21 Oct 2026 (3-year window, same features and settings: 300 trees, depth 4, 50 rows/leaf).
- **Per-horizon-group models** (`ml/train_grouped.py`): separate tree ensembles for 1-3, 4-7 and 8-14 days instead of one
  pooled model; `TreeModelPredictor` picks the group by `daysAhead` (old single-model files still load).
  Same 2026 holdout (6 May-21 Oct, 1,591 rows): direction 61.5% vs 54.6% for the previous shipped model and 57.8% for a
  plain retrain; error about 0.63% vs 0.65%. An earlier holdout (15 Dec 2025-6 May 2026) stayed below 50% for every
  design (42.6% grouped vs 42.1% pooled), so the edge is not stable; the 1-day call is no better than "no change".
- Old model backed up outside the repo; mini-budget-2022 stress test (42.5%) was set aside by the owner for this release.

## v78 — current rate on the Accuracy screen — 2026-10-23

The Accuracy screen now opens with "Latest GBP/USD": the hourly Alpha Vantage reading the price-alert worker already stores on
the phone (the user's own key, no extra call) while it is under 3 hours old, else the ECB rate via Frankfurter, plus the
% move since the newest stored row, so the track record can be read against where the pound is now.

## v77 — fresher Brent + data upkeep — 2026-10-23

- **Brent:** a daily recorder (Oilfetcher `fetch-brent-latest.yml`, weekdays 19:45 UTC) stores one OilPriceAPI Brent
  reading a day in `data/brent-latest.json`. The app uses it for `brentChange5d` once it holds enough history for its own
  5-day change (first possible ~30 Oct 2026); until then it falls back to the EIA series (2-3 days behind). The two
  series are never mixed inside one change (futures vs spot basis).
- **VIX / US 2yr:** the macro Action runs 4x a day (Mon-Sat) and retries FRED timeouts, so VIX is at most one day behind.
- Diagnostics wording fixed (headline text is now shown with links to the publisher).

## v76 — Google Play readiness — 2026-10-22

- **New application ID** `com.andypeak.gbpusdpredictor` (a separate install from the old dev build; carry forecast
  history over with Diagnostics > Forecast history > Export/Import). Code package unchanged.
- **Target API 36**; toolchain bumped to Kotlin 2.3.21, Room 2.8.5, AppCompat 1.7.1.
- **No API keys in the app.** US 2yr yield and VIX are read from JSON files published daily by the Oilfetcher
  GitHub Action (`MacroService`); `FREDService` removed. Price alerts use a free Alpha Vantage key the user enters in
  Price Alert settings (stored on the device); alerts stay off until a key is saved.
- **AdMob banner on every screen** (`ScreenHost`, outside the scrolling content) with Google UMP consent for UK/EEA
  and a "Privacy & ad choices" entry. Debug builds always use Google's test ad IDs; `bundleRelease` refuses test IDs.
- **Edge-to-edge** insets handled for all screens (forced at API 35+), dark status/nav icons.
- **Diagnostics** screen groups Forecast history, Data fetch log, ad choices, sources/credits and disclaimer.
  Permanent "Informational only. Not financial advice." line on the main screen.
- **Release hardening:** upload-keystore signing (`keystore.properties`, gitignored), R8 minify + shrink with
  `proguard-rules.pro`, HTTP logging off in release.
- `play/` folder: privacy policy, listing text, Data safety answers, 512 icon and 1024x500 feature graphic.

## v75 — make it obvious when the forecast includes the news — 2026-10-21

When the news-sentiment overlay is applied, the forecast now says so: the forecast text turns blue, gains a
"★ INCLUDES NEWS SENTIMENT" line and a breakdown ("model alone 1.3342 (+0.05%) + news +0.10% = 1.3355"), and
the advice line and the £-conversion line are tagged "(includes news sentiment)" / "incl. news sentiment". With the
overlay off (or on but no recent reading) it says "(model only)". Compare-horizons view marks each line with ★.

## v74 — on-phone controls for the news weighting — 2026-10-21

Two sliders and a reset button appear under the "Use news sentiment" toggle (only while it is on):
**News weight** (0 to 1.5 x the typical move; default 0.75) and **Full strength at N stories** (1 to 6; default 3).
Saved on the phone; the forecast re-renders live as a slider moves. Defaults are the v73 values.

## v73 — stronger news sentiment, test button removed — 2026-10-21

- **Removed the "Test the effect" button** and all test-override code (`cycleTestMode`, the simulated
  readings). The overlay now only ever uses the real published reading.
- **Weight raised** so the real news is visible in the forecast: `WEIGHT_OF_HORIZON_SD` 0.25 -> 0.75 and
  `FULL_STRENGTH_ARTICLES` 5 -> 3 (same-story duplicates are now merged by the publisher, so 3 stories is a
  real spread). At today's reading (+0.41 from 3 stories) the 1-day forecast moves about +0.10%
  (0.75 x 0.41 x the 0.33% typical 1-day move); a full-strength reading would move it about +-0.25%, more than
  the model's own typical 1-day call (~0.12%). **Still a judgement value, not fitted or backtested** -- a bigger
  weight means more influence from an untested signal.
- New bold line under the toggle, "News effect on the pound: 1d +0.10%", for the selected horizon(s).

`versionCode`/`versionName` 72 -> 73.

## v72 — daily sentiment feed from its own repo, with a daily notification — 2026-10-21

- The sentiment publisher now lives in its own public repo, **`andypeak84/sentiment`** (was briefly in
  Oilfetcher, since removed from there). Its GitHub Action runs **once a day at 06:00 UTC** and
  publishes `data/sentiment.json`; the app reads it from there (`SentimentService`).
- **Daily fetch + notification** (`SentimentScheduler` / `SentimentWorker`): at 08:00 UTC the app
  fetches the file, refreshes the cached reading the forecast overlay uses, and posts a "GBP/USD news
  sentiment" notification. Two hours after the Action, because GitHub's scheduler is best-effort.
  Runs only while the "Use news sentiment" toggle is on; turning it off cancels the chain.
- `MAX_AGE_HOURS` 6 -> 30: the file is now daily, so the old 6-hour limit would have ignored it.
- BBC stories are published with our own one-line reason instead of BBC's headline text; the screen
  and the notification show that reason.
- Publisher side: same-story duplicates are merged and counted once (the Jackdaw story, carried by two
  BBC feeds, no longer counts twice: 24h score 0.54 from 4 -> 0.41 from 3 stories).
- Free: GitHub Actions is free for public repos; no Firebase needed.

`versionCode`/`versionName` 71 -> 72.

## v71 — move the sentiment toggle to the top of the screen — 2026-10-21

The v70 toggle sat just above the forecast text, below the "Amount to convert" box and off the
bottom of the screen on a normal phone, so it was not found. Moved to directly under "Current
rate", above the chart. No logic change. `versionCode`/`versionName` 70 -> 71.

## v70 — optional news-sentiment overlay on the forecast — 2026-10-21

Wires the separate `gbpusd-sentiment` project (RSS news -> scored for GBP/USD -> time-
decayed -1..+1 score) into the app as a **toggle on the main screen**. When on, it
shifts the displayed forecast (and therefore the advice line) by a bounded amount; the
model's own number is shown alongside, e.g. `[model +0.05% / news -0.03%]`.

### What it is, and what it is not
It is **not** a trained model input. There is no multi-year sentiment history to train
on (the pipeline has run for days) and it has never been backtested. It is a labelled,
judgement-sized nudge: `0.25 x horizon_sd x score x min(1, articles/5)` -- at most about
+-0.08% at 1 day. **Default off.** `SentimentAdjustment.WEIGHT_OF_HORIZON_SD` is a
judgement value, not a fitted one. The publisher keeps `data/sentiment-history.json` so
that after some weeks it can be tested against what GBP/USD actually did, and the weight
either justified or set to zero.

### Honest state of the signal today
First live run of the pipeline (it had never run against real feeds): all 4 feeds
(BoE, Fed, 2x BBC) work, 156 items ingested, 57 relevant -- but the **offline rule
scorer rates nearly everything neutral at confidence 0**, so the real 24h reading is
about 0.00 from 1 article, and toggling it does almost nothing yet. A better scorer is
what makes the toggle matter: set the `ANTHROPIC_API_KEY` repo secret on Oilfetcher
(the Claude scorer is already built into `scorer.py`; scores are cached so each headline
is scored once) and/or keep tuning the rules from the `ratings.csv` hand-labels. In the
meantime a **test button** on the toggle simulates bullish/bearish news locally so the
effect can be seen. The rule scorer also disagrees with the owner's own ratings on UK
inflation (it calls rising UK inflation bullish; the owner rates it bearish) --
unchanged here, noted for the scorer-tuning work.

### Added
- **Oilfetcher repo** (public data host the app already reads): `sentiment/` (copies of
  the shared modules, refreshed by `gbpusd-sentiment/sync_to_oilfetcher.py` -- same
  pattern as `firebase/sync_core.py`), `scripts/publish_sentiment.py` (stateless
  ingest -> score -> aggregate, with a score cache and a scorer-signature check so a
  scorer edit invalidates it), and `.github/workflows/publish-sentiment.yml` (hourly,
  rebase-retry push since the oil workflow commits to the same branch). Writes
  `data/sentiment.json`, `data/sentiment-history.json`, `data/sentiment-cache.json`.
  Never overwrites a good signal if every feed fails. **BBC headlines are scored and
  counted but not republished** in the public JSON (BBC RSS terms are personal /
  non-commercial; a public repo is republication) -- central-bank items are.
- `network/SentimentService.kt` (raw GitHub fetch with the v68 `cacheBust`),
  `sentiment/SentimentRepository.kt` (parse, cache, 6h max age, test override, tilt maths),
  `TreeModelPredictor.horizonSdPct()`, and the toggle/status/test-button section in
  `MainActivity.kt`.

### Not changed
The tree model, the stored predictions and the Accuracy screen: the overlay is display-
only, so `predicted14dChangePct` stays the model's own number and accuracy tracking is
not contaminated. The daily notification and home-screen widget also stay model-only.

`versionCode`/`versionName` 69 -> 70.

## v69 — add hourly live-rate price alerts — 2026-10-17

New feature, independent of the daily forecast/prediction pipeline entirely: an
optional hourly check of the live GBP/USD rate, with a notification if it crosses a
level you set or moves more than a % you set within an hour.

### Why a new data source
Every existing source (FRED, Frankfurter, the GitHub-Action fetchers) is daily-
granularity by design -- MarketRepository's whole refresh model is anchored to
`previousBusinessDay()`, once a day. An alert that should fire within the hour can't
wait on that. Added **Alpha Vantage**'s `CURRENCY_EXCHANGE_RATE` endpoint (free key,
25 requests/day -- comfortably covers hourly polling with a request to spare) as a
third, independent source used only by this feature.

### Added
- `network/AlphaVantageService.kt` -- Retrofit service + response models. Alpha
  Vantage returns HTTP 200 with an "Error Message"/"Note"/"Information" field instead
  of a 4xx/5xx for a bad key or rate-limit hit -- checked explicitly in
  `fetchLiveRate()` rather than relying on retry-on-IOException logic to ever catch it.
- `work/PriceAlertWorker.kt` + `PriceAlertScheduler.kt` -- hourly self-rescheduling
  OneTimeWorkRequest chain, same drift-resistant pattern as `MarketDataScheduler`
  (periodic WorkManager requests drift under Doze; a chain that recomputes "next hour
  from now" every run doesn't compound that drift), just anchored to a flat 60-minute
  interval instead of a daily UTC clock time. Entirely separate unique-work name from
  the daily chain -- two independent chains.
- `work/PriceAlertPrefs.kt` -- settings + state (enabled, upper/lower rate thresholds,
  hourly % move threshold, last hourly rate for the % comparison, last
  attempt/outcome for diagnosability).
- `PriceAlertSettingsActivity.kt` (new "Price Alerts" button on the main screen) --
  set either or both trigger kinds, independently optional. Includes a **Send Test
  Alert** button that fetches the live rate immediately and force-posts a
  notification, bypassing the hourly wait and any threshold check, to verify the
  whole pipeline (API key, network, POST_NOTIFICATIONS permission) works on demand.
- New `price_alert` notification channel, `IMPORTANCE_HIGH` (unlike the daily
  forecast's `DEFAULT`) -- an alert is by definition something the user asked to be
  interrupted for.

### Not touched
No change to `market_data`, the tree model, or the daily refresh -- this is a fully
separate live watch-and-alert, not a new prediction input.

`versionCode`/`versionName` 68 → 69.

## v68 — fix stale oil/Brent data: GitHub's raw-content CDN ignores client no-cache — 2026-10-16

Prompted by a live accuracy check: v67's last 4 predictions in a row were all misses,
all in the same direction (bearish/flat while GBP/USD climbed 4 straight sessions).
Dug into why -- confirmed that's the already-known, accepted longer-trend-feature lag
(10/20-day windows still mostly spanning the September decline), not a bug. But the
same dig surfaced a real, separate bug: `oilChange5d`/`brentChange5d` were null on
several recent rows, and `bootstrap_fetch_log` showed the oil/Brent fetch pinned to
**2026-10-06's value for 5 straight days** (10-06 through 10-14) despite running daily
without error.

Root cause: `andypeak84/Oilfetcher`'s GitHub Action ran successfully every day and its
committed `data/oil-price.json` WAS fresh every day (verified directly against FRED's
own API -- FRED had distinct values for every date in the stuck window, so this
wasn't FRED stalling like v66's Brent-add story). The bug was entirely client-side:
`raw.githubusercontent.com` is served through GitHub's own Fastly CDN, which caches
by URL **at its own discretion** and does **not** honour a client-sent `Cache-Control`
request header for that decision -- that header only ever governs intermediate
proxies the client itself controls, never the origin's own CDN. `OilPriceService.kt`/
`BrentPriceService.kt`/`GiltYieldService.kt` all sent `Cache-Control: no-cache`
believing it would do this (see their own prior comments) -- it does nothing against
Fastly. The app was stuck re-reading one Fastly edge's cached copy for 5 days until
that edge's cache happened to expire or get missed on its own.

### Fixed -- cache-busting query parameter
Added `@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()` to all three
raw.githubusercontent.com-backed services (`OilPriceService`, `BrentPriceService`,
`GiltYieldService` -- the last hasn't shown the symptom yet, but it's the identical
CDN and identical risk, fixed pre-emptively). A different query string on every
request is something Fastly's cache key DOES respect, unlike a request header --
the one reliable way to defeat this from the client side. Default parameter value
means no call-site changes needed at `MarketRepository.kt`'s three call sites.

### Not changed
The longer-trend-feature reversal lag found during the same accuracy check (see
`train_model.py`'s `FEATURE_ORDER` comment) -- that's the known, explicitly-accepted
v67 tradeoff playing out live, not a bug, and should self-correct as the 10/20-day
windows roll past the September decline.

`versionCode`/`versionName` 67 → 68.

## v67 — longer-trend momentum features + systematic hyperparameter sweep — 2026-10-07

Prompted by: "can it be made more accurate, the misses are quite large" -- three real
live misses (Sep 21/28/30) were all the model calling stabilization mid a genuinely
sustained decline; it only ever saw 1d/5d momentum windows, with no way to tell
"still trending" from "noise" over a longer stretch. Addressed in two parts, both
explicitly requested together ("can we do 1 & 2").

### 1. Added — `gbpUsdChange10d`, `gbpUsdChange20d`, `spread2yChange10d`
Zero new fetches -- same `gbpHistory`/`spreadSeries` already pulled for the 1d/5d
versions, just a longer `changeNDaysAgoFromSeries()` window. `data/MarketData.kt` --
3 new nullable fields; DB `version 10 → 11` (real `ALTER TABLE` migration, not
destructive -- see `AppDatabase.kt`'s `MIGRATION_10_11`, same reasoning as v66's
`MIGRATION_9_10`). `HistoryActivity.kt` -- 3 new columns.

### 2. Systematic hyperparameter sweep (not hand-tuning)
Built the dataset once, trained 9 num_trees/max_depth/shrinkage/min_examples
configs against the identical data/holdout split, compared 1-day accuracy +
calibration (correlation, slope) + RMSE. New default: **`num_trees=300,
shrinkage=0.015`** (was 150/0.03, same max_depth=4/min_examples=50) -- ties the old
default's 1-day accuracy exactly but with meaningfully better calibration (corr
+0.042→+0.050, slope 0.111→0.138). Not a dramatic win; a systematically-found edge,
strictly as-good-or-better on every 1-day metric.

### Known tradeoff — found via ablation, kept deliberately
A 4-way ablation (old/new features × old/new hyperparameters, same stress-test data)
isolated the longer-trend features as the whole story behind an initially alarming
mini-budget-crisis stress-test regression:

| | 1-day holdout | COVID 2020 | Mini-budget 2022 |
|---|---|---|---|
| Old features, old hp (pre-v67) | ~55% | 44.5% | 54.3% |
| New features, new hp (shipped) | **57.5%** | 48.2% | **42.0%** |
| Old features, new hp (hp only) | 51.7%* | 47.9% | 56.6% |

*(*without the longer-trend features, 1-day accuracy is near coin-flip regardless of
hyperparameters -- the features are carrying essentially all of the 1-day gain.)*

A 10-20 day trend feature is exactly the wrong tool for a sharp V-shaped reversal
(sterling cratered then partly recovered through Sep-Nov 2022) -- it keeps encoding
"still falling" right as the trend violently reverses. Explicit choice: keep the
features. Day-to-day 1-day accuracy near coin-flip (the fix this round was actually
asked for) was judged worse than fragility during one rare multi-week crisis window.
See `ml/train_model.py`'s `FEATURE_ORDER` comment for the full reasoning if this
needs revisiting later (e.g. a shorter window, or reversal-aware down-weighting).

### Retrained and verified
25 features (22 + the 3 above), `--include-uk2y`, export self-verification passed.
`ml/train_model_colab.ipynb` re-synced (core cell + hyperparameter cell).
`versionCode`/`versionName` 66 → 67.

## v66 — add Brent crude as a second oil benchmark — 2026-10-05

Prompted by a live discussion about pump/diesel prices hitting record highs while
`oilChange5d` (WTI) had been showing declines -- checked Brent crude (the actual
UK/global benchmark fuel pricing tracks, not WTI, a US-specific Cushing-landlocked
price) and found it told a different story: Brent ran **$15-25/barrel above WTI**
through September 2026, and crucially **FRED's Brent series stayed current the whole
time WTI's stalled for over a week**. Added as a second, independent benchmark
alongside WTI, not a replacement -- the two carry different information (regional/
geopolitical risk premiums show up in Brent first; US-specific supply/storage
dynamics in WTI).

### Added -- `brentChange5d`
Mirrors `oilChange5d` exactly, same files, same pattern, same 1-business-day anchor
tolerance:
- **Separate repo (`andypeak84/Oilfetcher`)**: `fetch_oil_price_fred.py` generalised
  to fetch both WTI and Brent (FRED `DCOILBRENTEU`, EIA `RBRTE` fallback), writing a
  new `data/brent-price.json` alongside the existing `data/oil-price.json` (unchanged
  shape/behavior). Verified live with a manual `workflow_dispatch` run.
- `app/.../network/BrentPriceService.kt` (new) -- same Retrofit/raw-text pattern as
  `OilPriceService.kt`.
- `repository/MarketRepository.kt` -- `fetchBrentHistoryFromGitHub()` mirrors
  `fetchOilHistoryFromGitHub()` exactly (same enriched staleness logging); `brentChange5d`
  computed in `refreshData()` with `anchorToleranceDays = 1`, same as `oilChange5d`.
- `data/MarketData.kt` -- new nullable field; DB `version 9 → 10`.
- `MainActivity.kt` / `HistoryActivity.kt` -- labels updated to "Oil (WTI)" / "Oil
  (Brent)" to disambiguate now that there are two.
- `ml/train_model.py` -- fetches `DCOILBRENTEU`, computes `brentChange5d` via the
  already-source-agnostic `compute_oil_change_5d()` (no changes needed to that
  function itself), added to `FEATURE_ORDER`. `ml/train_model_colab.ipynb` re-synced.

### Retrained and verified -- real numbers
Same shipped hyperparameters, 22 features now (21 + `brentChange5d`).

- **10,577 rows** (2023-10-06 to 2026-10-01), 8,990 train / 1,587 test.
- `brentChange5d` null rate: **0.47%** (42/8,990) -- healthy, matching `oilChange5d`'s
  own rate on this run, not a source-specific reliability problem.
- `brentChange5d` importance: **1,033** vs `oilChange5d`'s 1,096 -- comparable, neither
  dominates (no leakage red flag).
- **Holdout directional accuracy: 58.5%** (previous: 59.0%) -- flat, within noise.
- **Ablation check**: re-ran with `brentChange5d` excluded from `FEATURE_ORDER` (same
  data window) specifically to isolate its effect after the 1-day calibration slope
  came out much weaker than the last run (0.041 vs the previous 0.47). The ablation
  run showed the **same weak 1-day slope (0.044, corr +0.018) with Brent excluded
  entirely** -- so this is NOT caused by adding Brent, it's this window's holdout
  period genuinely containing more of the choppy late-September stretch already
  being tracked live (three misses in a row Sep 21-23). Every other horizon (5-14
  day) matched closely between the two runs. Brent is safe to ship.
- **Export self-verification: 1,587/1,587 rows matched** (median error 0.00000002).
- **Stress tests**: `covid_crash_2020` 47.9% (previous 44.5%, within noise),
  `mini_budget_crisis_2022` 55.7% (previous 54.3%, within noise).

Model copied to `app/src/main/assets/market_predictor_trees.json`. `versionCode`/
`versionName` bumped 65 → 66. As always: `gradle clean assembleDebug` + reinstall
needed before this reaches the phone.

## v65 — fix oversized/miscalibrated forecasts + EIA oil fallback — 2026-09-24

### Fixed -- forecasts were 3-7x too large at short horizons
Live results made this obvious: predicted -0.58% vs actual -0.20%, -0.60% vs -0.08%,
-0.40% vs -0.10% -- direction right, size badly wrong. Verified structural (not a
small-sample fluke) by replaying the shipped model over the holdout: the model output
roughly the SAME size move (~0.3%) at every horizon from 1 to 14 days, while real moves
grow ~3x over that range. Calibration slope (actual regressed on predicted, 1.0 =
perfect): **0.21 at 1 day, 1.77 at 14 days** -- both far from 1.0.

Cause: one pooled model trained on all 14 horizons at once, with an un-scaled target.
Squared-error loss weights a 14-day row (de-meaned sd ~1.5%) about 11x more than a
1-day row (~0.44%), so the ensemble ends up fit mostly to long-horizon magnitudes and
reuses that same size move at short horizons. The app stores/scores/notifies on the
**1-day** horizon specifically, so this mattered most exactly where it was worst.

`ml/train_model.py` -- `train_and_export()` and `evaluate_stress_period()`:
- Per-horizon standardisation, train-split only (same no-leakage discipline as the
  existing `horizon_baseline` de-meaning): divide the de-meaned label by
  `train_df.groupby("daysAhead")[label].std()` before training. Trees/bias stay on
  this z-scale in the export; direction is unaffected (sign invariant to a positive
  rescale).
- `horizon_mae` recomputed in raw units (the z-scale error times that row's
  `horizon_sd`) so the app's ± band still shows a real percentage.
- New export field `"horizon_sd"` (string-keyed by daysAhead, same shape as
  `horizon_baseline`/`horizon_mae`).
- New **calibration table** printed every run (n, sd(pred), sd(actual), corr, slope,
  dir_acc, advice-fire-rate per horizon) -- reproduces the analysis that caught this in
  one command, so a future regression is caught at retrain time, not from live results.

`app/.../ml/TreeModelPredictor.kt`:
- Parses optional `horizon_sd` (absent/missing-key -> 1.0, a no-op, so older model
  files behave exactly as before).
- `predictChangePct`: `baseline + sd * (bias + sum(trees))` (was `bias + baseline +
  sum(trees)`).
- New `adviceCutoffPctFor(daysAhead)` = `ADVICE_CUTOFF_SD_FRACTION * horizonSd[h]`,
  replacing three separate hard-coded `0.0015` (a fixed 0.15%) "Wait / Convert now /
  broadly stable" cutoffs in `MainActivity.kt`, `MarketDataWorker.kt`, and
  `PredictorWidgetProvider.kt` -- previously the SAME fixed cutoff applied regardless
  of horizon, which fired on ~73% of 1-day forecasts once predictions were inflated.
  Falls back to the legacy fixed 0.0015 when a model has no `horizon_sd` block.
- `ADVICE_CUTOFF_SD_FRACTION` tuned empirically, not guessed: 0.15 measured a 63-66%
  fire rate on the real holdout (still too eager -- predictions' own sd is much
  smaller than a horizon's actual sd, since the model correctly stays humble when the
  real signal is weak). **0.35 measured 34-36% fire rate across every horizon** on the
  same holdout -- shipped value, so "broadly stable" is the common case.

### Retrained and verified -- real numbers
Same shipped hyperparameters (3-year window, 150/4/0.03/50, `--include-uk2y` with
levels), 21 features, isolated Python 3.12 + `ydf==0.8.0` environment (unchanged setup
from the prior two retrains this project).

- **10,605 rows** (2023-09-25 to 2026-09-22), 9,014 train / 1,591 test.
- **Calibration, before -> after** (raw %, holdout): slope 1d 0.21 -> **0.47**, 14d 1.77
  -> **1.05**. sd(pred) now actually grows with horizon (0.126% at 1d up to 0.415% at
  14d) instead of sitting flat (~0.26-0.30% everywhere before) -- matches the shape of
  sd(actual) (0.375% -> 1.050%), just correctly smaller given the real correlation.
- **Holdout directional accuracy: 59.0%** (previous: 55.6%) -- an improvement, though
  with a ~1,591-row holdout part of this is likely ordinary variation rather than a
  clean signal gain; not over-claiming causation.
- **Export self-verification: 1,591/1,591 rows matched** (median error 0.00000002).
- **Stress tests** (separate 2018-onward frame, same hyperparameters):
  `covid_crash_2020` 46.3% (previous 44.5%, within noise), `mini_budget_crisis_2022`
  59.2% (previous 54.3% -- another improvement, same "don't over-claim from a noisy
  holdout" caveat as above).
- Feature importances unchanged in shape/ranking (`ukRate` top, `gbpUsdRealVol10d`
  2nd, no single feature running away with importance) -- only the absolute SUM_SCORE
  numbers look different now (thousands, not 0-1), an artefact of the label's variance
  changing from ~0.0001 (raw %) to ~1 (z-score), not a real shift in what matters.

Model copied to `app/src/main/assets/market_predictor_trees.json`.

**Note for reading the Accuracy screen after this update**: rows written by the OLD
model keep their old (oversized) `predicted14dChangePct` forever -- nothing retroactively
recalculates stored predictions. Only NEW rows, from the next 21:00 UTC refresh onward,
reflect this fix. Expect the Accuracy screen's predicted-vs-actual size ratio to look
mixed for a while as old rows roll off.

### Fixed -- versionCode/versionName were never bumped for the prior oilChange5d fix
`app/build.gradle`: `versionCode`/`versionName` jump **63 -> 65** (skipping 64
deliberately) because the previous oilChange5d anchor-tolerance fix shipped without
this bump at all -- the phone kept showing "v63" days after that fix was genuinely
live and working, causing real confusion mid-debugging-session (caught from a live
screenshot of the phone). Every future change needs to bump this, no exceptions.

### Added -- EIA fallback for oil (separate repo: `andypeak84/Oilfetcher`)
FRED's `DCOILWTICO` has stalled twice in two weeks now (most recently 6+ days). EIA's
`RWTC` is the original series FRED mirrors, confirmed live to already be less laggy
during the second stall (EIA had through 2026-09-22 while FRED was still stuck at
2026-09-15 at the time of checking).

`Oilfetcher/scripts/fetch_oil_price_fred.py`:
- Tries FRED first, unchanged. If FRED's newest observation is more than
  `ANCHOR_TOLERANCE_BUSINESS_DAYS` (1, kept in sync with `MarketRepository.kt`'s own
  `anchorToleranceDays` for `oilChange5d`) business days stale, also fetches EIA's
  `RWTC` and adds any date FRED doesn't have -- FRED entries are never overwritten,
  so a normal day behaves identically to before.
- Optional `EIA_API_KEY` repo secret (added); if unset, the fallback is silently
  skipped and behavior is unchanged from before this update.
- `data/oil-price.json`'s shape is untouched -- no Android-side changes needed at all,
  `OilPriceService`/`MarketRepository` are agnostic to which upstream produced a row.
- `.github/workflows/fetch-oil-price.yml`: passes `EIA_API_KEY` through alongside the
  existing `FRED_API_KEY`.
- Verified live with a manual `workflow_dispatch` run: correctly detected FRED as 2
  business days stale, queried EIA, found EIA already had the same dates FRED did
  (added 0 duplicates), committed cleanly.

## v64 — fix oilChange5d going null + retrain — 2026-09-16

`oilChange5d` had been intermittently null for ~4 months. Root-caused (not guessed) two
compounding things, both now fixed:

1. **The real bug**: `changeNDaysAgoFromSeries()` required an EXACT, zero-tolerance
   match on the anchor date (`targetDate`) -- `seriesByDate[latestDate] ?: return null`.
   `toleranceDays` only ever applied to the reference point (5 business days earlier),
   never to the anchor -- contrary to a comment on `TARGET_LAG_BUSINESS_DAYS` claiming
   "that fetch's own tolerance absorbs its lag independently." That claim was never
   actually true in the code until this change.
2. **A live, confirmed FRED/EIA outage**: at the time of this fix, FRED's own metadata
   for `DCOILWTICO` reported `last_updated: 2026-09-10`, `observation_end: 2026-09-09` --
   no new observation in 6+ days. `Oilfetcher`'s Action was running correctly and
   committing daily throughout (confirmed via its commit history); it simply had nothing
   new to fetch. Nothing in this codebase can make FRED publish -- this is upstream and
   outside anyone's control here.

**A large tolerance bump (5-7 business days) was considered and rejected.** Oil's real
2026 publish history (172 observations, Jan-Sep) is genuinely daily, with every gap
`>3` calendar days being exactly 4 -- a single US federal holiday Monday (~7 times in
8.5 months) -- right up until the current, unprecedented outage. A big blanket tolerance
would do nothing useful on normal days (1-day lag already fits inside the existing
2-day window) and would be actively harmful whenever it DID kick in: pairing a week-old
oil reading with every other field's genuinely-current one under one shared date label
is exactly the misaligned-timestamp leak this project already eliminated elsewhere (the
ffill bug, the all-null-row bug).

### Fixed -- small (1 BUSINESS day, not calendar day), oil-only anchor tolerance
`repository/MarketRepository.kt`

- `changeNDaysAgoFromSeries()` gains `anchorToleranceDays: Int = 0` (default preserves
  exact-match behavior for every other caller -- gbpUsd/us2y/vix/spread2y/the FX crosses
  keep zero-tolerance anchoring; their real-world lag genuinely is that tight and the
  invariant matters). When `> 0`, finds the nearest real reading `<=` the target date,
  measured in BUSINESS days via `AppClock.businessDaysBetween` -- deliberately business
  days, not calendar days: a single Monday holiday makes the preceding Friday 3 calendar
  days back but only 1 business day back, and `anchorToleranceDays=1` is sized to that
  exact, confirmed real-world case, not an arbitrary number. Never picks a reading AFTER
  the target date (no look-ahead).
- Only `oilChange5d`'s call site passes `anchorToleranceDays = 1`.
- `fetchOilHistoryFromGitHub()`'s `NO_MATCH` outcome now folds in how stale the source
  itself is (`"NO_MATCH (source N business days stale)"`), reusing
  `AppClock.businessDaysBetween` -- a future occurrence reads as "the source is stale"
  at a glance on the Bootstrap Log screen instead of a bare, ambiguous `NO_MATCH`.
- Corrected the `TARGET_LAG_BUSINESS_DAYS` comment's previously-false claim about oil's
  tolerance absorbing its own lag -- now actually true, in a small, deliberate way.

### Fixed -- train/serve consistency for oilChange5d
`ml/train_model.py`

- Replaced `frame["oilPrice"].pct_change(5, fill_method=None)` (strict positional on
  the business-day-reindexed frame -- actually STRICTER than serving was even before
  this fix, its own small pre-existing inconsistency) with `compute_oil_change_5d()`, an
  explicit per-row port of `changeNDaysAgoFromSeries`'s oil-specific call
  (daysAgo=5, toleranceDays=2, anchorToleranceDays=1), operating on oil's own native
  (sparse) dates. Written as a direct, checkable mirror rather than a cleverer
  vectorized `merge_asof` pipeline, verified against synthetic data covering all four
  cases (normal day, single-holiday fallback, genuine multi-day outage staying null,
  no look-ahead) before wiring into the real pipeline.
- `ml/train_model_colab.ipynb` core cell re-synced, per convention.

### Fixed -- separate repo: `andypeak84/Oilfetcher`
`.github/workflows/fetch-oil-price.yml`

- Cron moved from `30 20 * * *` (20:30 UTC, intended 30 min ahead of the app's
  21:00 UTC refresh) to `0 18 * * *` (18:00 UTC). The Action's own run-time history
  showed it consistently firing 1.5-3 hours late (22:13-23:19 UTC) -- a documented
  GitHub `schedule`-trigger limitation, not a bug in the workflow -- often AFTER the
  app's refresh, defeating the original margin. Not the cause of the current episode
  (Oilfetcher already matched FRED exactly throughout), but a real secondary risk on a
  day FRED does publish on time.

### Retrained and verified -- real numbers
Same shipped config as before (3-year window, `num_trees=150 max_depth=4 shrinkage=0.03
min_examples=50`, `--include-uk2y` with levels), 21 features, using the isolated Python
3.12 + `ydf==0.8.0` environment set up for the previous retrain (`ydf>=0.9` has no
Windows wheels at all; 0.8.0 is the last one, and tops out at Python 3.12).

- **oilChange5d's null rate dropped from 8.25% to 0.78%** (742 -> 70 of ~9,000 training
  rows) -- now matching `gbpUsdChange5d`'s and `vixChange5d`'s baseline null rate almost
  exactly, i.e. only the structural "not enough history yet" nulls every `*Change5d`
  feature has, not a source-specific problem anymore.
- Spot-checked `training_data.csv` directly against the live outage: Sep 9->10 (a
  normal 1-business-day gap) now correctly reuses Sep 9's real reading; Sep 11 onward
  (2+ business days into the confirmed FRED outage) correctly stays null. Exactly the
  intended behavior in both directions.
- **Holdout directional accuracy: 55.6%** (previous: 55.4%) -- flat, as expected for a
  reliability fix, not a signal change.
- **oilChange5d importance: 0.209** (previous: 0.212) -- essentially unchanged; fixing
  its reliability didn't cause it to dominate or misbehave.
- **Export self-verification: 1,589/1,589 rows matched** (median error 0.0, max error
  0.00006 -- ordinary float-rounding boundary noise).
- **Stress tests** (separate 2018-onward frame, same hyperparameters): `covid_crash_2020`
  44.5% (previous 46.3%, within noise of a different row composition), `mini_budget_
  crisis_2022` 54.3% (previous 52.7%). Both consistent with prior findings -- covid
  remains a genuine weak spot, mini-budget remains modestly above chance.

Model copied to `app/src/main/assets/market_predictor_trees.json`. As always: `gradle
clean assembleDebug` and reinstall needed before this reaches the phone -- a stale
`app/build` will silently keep the old model/version (see the v63 build-staleness
lesson).

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
