# To do

## Retrain the model: spread changes in points, and test seasonality (noted 2026-11-25)

**Spread changes in points.** The model's three spread inputs (`spread2yChange1d`, `spread2yChange5d`, `spread2yChange10d`) have always
been the % change of the UK-US 2-year spread (`ml/train_model.py`, `frame["spread2y"].pct_change(...)`, lines ~546-549; the app's
`MarketRepository` does the same). Near a zero spread that % explodes: on 25 Nov 2026 the 10-day change was -1695% for a move from about
-0.003 to +0.050 (+0.053 points). The training data has the same problem (values down to about -16,500%). Since v104 the screen shows the
move in points, but the model still gets the %.
- Change the three lines in `ml/train_model.py` from `.pct_change(n)` to `.diff(n)` (points).
- Change the matching lines in `MarketRepository.captureDate` (the spread changes use `changeNDaysAgoFromSeries`, which returns a % change;
  add a points version) so the app sends points too.
- Retrain in Colab (`ml/train_model_colab.ipynb`; ydf does not install on this PC's Python 3.14), needs the FRED API key, then
  `ml/train_grouped.py --lag-stale` as for v87.
- Compare against the current model on the same holdout windows and the walk-forward test before replacing
  `app/src/main/assets/market_predictor_trees.json`.

**Seasonality, in the same retrain.** The model has no date input (calendar features were dropped) and its training data covers only
24 Oct 2023 to 21 Oct 2026, so it has seen three late Novembers. 21 years of GBP/USD (2005-2025) show the pound up 14 of 21 years over
25 Nov to 4 Dec (+0.60% on average, against 50% and -0.03% for any 7-day window) and April up 17 of 22 years (+1.33%). Testing a month
input properly needs a training set back to about 2005, rebuilt from the FRED / Treasury / BoE histories.

**Other inputs to consider at the same time:** VIX now has a Cboe backup (v102); a VIX 10-day change could be tested alongside.
