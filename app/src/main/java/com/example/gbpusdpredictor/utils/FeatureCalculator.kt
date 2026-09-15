package com.example.gbpusdpredictor.utils

object FeatureCalculator {
    fun calculateSpread(ukYield: Double, usYield: Double): Double {
        return ukYield - usYield
    }
}
