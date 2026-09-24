package com.example.gbpusdpredictor.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads and evaluates the gradient-boosted-trees model trained offline by
 * ml/train_model.py, exported as a plain JSON tree structure (see
 * market_predictor_trees.json) rather than TFLite.
 *
 * Why not TFLite: TF-DF/YDF's tree-inference ops are custom C++ kernels that were never
 * built to be TFLite/Flex compatible (confirmed both by a real conversion failure and by
 * Google's own team on their AI developer forum -- this is a known, unresolved limitation,
 * not a version-pinning problem). A gradient-boosted-trees model is fundamentally just a
 * set of numeric if/else comparisons, so this walks the exported tree structure directly --
 * no TensorFlow, no protobuf, no native library, nothing that can version-conflict.
 *
 * Uses org.json (built into Android) -- no extra dependency.
 */
class TreeModelPredictor(context: Context) {

    companion object {
        private const val TAG = "TreeModelPredictor"
        private const val MODEL_FILE = "market_predictor_trees.json"
        // Used by adviceCutoffPctFor() only when the loaded model has no "horizon_sd"
        // block (pre-calibration-fix models) -- the original fixed cutoff every advice
        // call site used to hard-code directly. See adviceCutoffPctFor's own comment.
        private const val LEGACY_ADVICE_CUTOFF_PCT = 0.0015
        // How many standard deviations of a horizon's own typical move size the
        // predicted change must cross before advice ("Wait" / "Convert now") fires,
        // instead of "broadly stable". Matches ml/train_model.py's
        // ADVICE_CUTOFF_SD_FRACTION exactly (kept in sync manually -- that copy is for
        // train-time preview only, this one is what the app actually uses).
        // 0.15 was tried first and measured firing 63-66% of the time on the holdout --
        // far too eager, since predictions' own sd is much smaller than a horizon's
        // actual sd (the model correctly stays humble when the real signal is weak).
        // 0.35 measured 34-36% across every horizon on the same holdout -- picked so
        // "broadly stable" is the common case and advice reflects a real, sized-up call.
        const val ADVICE_CUTOFF_SD_FRACTION = 0.35
    }

    private class Node(
        val isLeaf: Boolean,
        val leafValue: Double = 0.0,
        val feature: String = "",
        val threshold: Double = 0.0,
        val missingGoesRight: Boolean = false,
        val left: Node? = null,
        val right: Node? = null
    )

    private var bias: Double = 0.0
    private var trees: List<Node> = emptyList()
    // Per-horizon average return, subtracted out during training so the trees learn only
    // genuine conditional signal instead of mechanically reproducing whatever drift the
    // training window's history happened to average out to (see train_model.py's
    // train_and_export() for the full reasoning). Added back here to get a real, usable
    // prediction. Keyed by daysAhead as a string (JSON object keys). Absent/empty for
    // models trained before this existed, or that don't use "daysAhead" at all -- treated
    // as 0.0 (no-op) for those, so older model files still work.
    private var horizonBaseline: Map<String, Double> = emptyMap()
    // OPTIONAL per-horizon mean absolute error of the % change prediction, measured on the
    // training run's own chronological holdout and written into the JSON as a
    // "horizon_mae" object keyed by daysAhead-as-string (same shape as horizon_baseline).
    // The current shipped model file does NOT contain this yet -- when it's absent this
    // map stays empty and maePctFor() returns null, so the UI simply shows no error band.
    // Regenerate the model with a "horizon_mae" block (see ml/README.md) to switch the
    // band on with zero further code changes.
    private var horizonMae: Map<String, Double> = emptyMap()
    // Per-horizon standardisation divisor from training -- the trees/bias are trained
    // on (rawChange / horizonSd[daysAhead]), a z-score, not the raw % change directly
    // (see train_model.py's train_and_export() for why: without this, one pooled model
    // trained on all 14 horizons ends up dominated by the longest ones and predicts
    // roughly the same size move at every horizon, regardless of how much bigger real
    // moves get at longer horizons). Multiplied back in at predictChangePct() to turn
    // the tree output back into a real, usable percentage. Absent/empty for models
    // trained before this existed -- treated as 1.0 (no-op) per key, so older model
    // files still work exactly as before (their trees were never z-scaled to begin
    // with, so multiplying by 1.0 is the correct no-op, not an approximation).
    private var horizonSd: Map<String, Double> = emptyMap()

    val isAvailable: Boolean get() = trees.isNotEmpty()

    init {
        try {
            val json = context.assets.open(MODEL_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            bias = root.getDouble("bias")
            val treesArray = root.getJSONArray("trees")
            trees = (0 until treesArray.length()).map { parseNode(treesArray.getJSONObject(it)) }
            horizonBaseline = root.optJSONObject("horizon_baseline")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.getDouble(key) }
            } ?: emptyMap()
            horizonMae = root.optJSONObject("horizon_mae")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.getDouble(key) }
            } ?: emptyMap()
            horizonSd = root.optJSONObject("horizon_sd")?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.getDouble(key) }
            } ?: emptyMap()
            Log.i(TAG, "Loaded $MODEL_FILE: ${trees.size} trees, bias=$bias, ${horizonBaseline.size} horizon baseline(s), ${horizonMae.size} horizon MAE(s), ${horizonSd.size} horizon sd(s)")
        } catch (e: Exception) {
            // Most common cause: market_predictor_trees.json hasn't been generated/copied
            // into app/src/main/assets/ yet. See ml/README.md.
            Log.e(TAG, "Failed to load $MODEL_FILE -- has it been trained and placed in assets/? See ml/README.md", e)
        }
    }

    private fun parseNode(obj: JSONObject): Node {
        return if (obj.has("leaf")) {
            Node(isLeaf = true, leafValue = obj.getDouble("leaf"))
        } else {
            Node(
                isLeaf = false,
                feature = obj.getString("feature"),
                threshold = obj.getDouble("threshold"),
                missingGoesRight = obj.optBoolean("missingGoesRight", false),
                left = parseNode(obj.getJSONObject("left")),
                right = parseNode(obj.getJSONObject("right"))
            )
        }
    }

    private fun walk(node: Node, features: Map<String, Double>): Double {
        if (node.isLeaf) return node.leafValue
        val value = features[node.feature]
        val goesRight = if (value == null) node.missingGoesRight else value >= node.threshold
        return walk(if (goesRight) node.right!! else node.left!!, features)
    }

    /**
     * @param features must contain a value for every feature name the trained trees
     *        reference (see the "feature_order" list in market_predictor_trees.json).
     *        Looked up by name, so the map's insertion order doesn't matter. If the model
     *        was trained with a "daysAhead" feature, include it to get a prediction for
     *        that specific horizon; older models trained without it will just ignore
     *        that key if it's present, harmlessly.
     * @return predicted % change in GBP/USD over whatever horizon the model was trained
     *         to predict (see the training run's notes), or null if the model isn't
     *         loaded or a required feature is missing.
     */
    fun predictChangePct(features: Map<String, Double>): Double? {
        if (!isAvailable) return null
        return try {
            val daysAheadKey = features["daysAhead"]?.toInt()?.toString()
            val baseline = daysAheadKey?.let { horizonBaseline[it] } ?: 0.0
            // 1.0 (no-op) when this model has no horizon_sd block, or no entry for this
            // specific horizon -- see horizonSd's own declaration.
            val sd = daysAheadKey?.let { horizonSd[it] } ?: 1.0
            baseline + sd * (bias + trees.sumOf { walk(it, features) })
        } catch (e: Exception) {
            Log.e(TAG, "predictChangePct failed", e)
            null
        }
    }

    /** Typical absolute error of a [predictChangePct] result at the given horizon,
     * expressed as a fraction (e.g. 0.004 = ±0.4%), or null if the loaded model file
     * carries no "horizon_mae" block. The caller turns this into a ± band on the predicted
     * RATE by multiplying by the current rate. Null is the normal case for the currently
     * shipped model -- see horizonMae's declaration. */
    fun maePctFor(daysAhead: Int): Double? = horizonMae[daysAhead.toString()]

    /** How large |predictChangePct] must be, at this horizon, before advice ("Wait" /
     * "Convert now") should fire instead of "broadly stable" -- ADVICE_CUTOFF_SD_FRACTION
     * of this horizon's own typical move size (its horizon_sd), so a weak-signal short
     * horizon (small typical move) gets a small cutoff and a longer horizon (bigger
     * typical move) gets a proportionally bigger one, rather than one fixed percentage
     * for every horizon regardless of how big a "real" move actually looks there.
     * Falls back to the original fixed LEGACY_ADVICE_CUTOFF_PCT when this model has no
     * horizon_sd block (pre-calibration-fix models) or no entry for this horizon, so
     * older model files keep their original behavior exactly rather than silently using
     * a nonsensical 0.15 * 1.0 = 0.15% for every horizon alike. */
    fun adviceCutoffPctFor(daysAhead: Int): Double {
        val sd = horizonSd[daysAhead.toString()]
        return if (sd != null) ADVICE_CUTOFF_SD_FRACTION * sd else LEGACY_ADVICE_CUTOFF_PCT
    }
}
