package com.xmusic.player.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AnalyzerState {
    private val _levels = MutableStateFlow(FloatArray(32))
    private val _peakL = MutableStateFlow(-60f)
    private val _peakR = MutableStateFlow(-60f)
    val levels = _levels.asStateFlow()
    val peakL = _peakL.asStateFlow()
    val peakR = _peakR.asStateFlow()

    fun publish(bins: FloatArray, leftDb: Float, rightDb: Float) {
        _levels.value = bins.copyOf()
        _peakL.value = leftDb
        _peakR.value = rightDb
    }
}
