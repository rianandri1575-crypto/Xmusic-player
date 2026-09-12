package com.xmusic.player.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object EqualizerState {
    private val _gains = MutableStateFlow(FloatArray(31))
    val gains = _gains.asStateFlow()

    fun set(index: Int, value: Float) {
        if (index !in 0 until 31) return
        val a = _gains.value.copyOf()
        a[index] = value.coerceIn(-12f, 12f)
        _gains.value = a
    }

    fun setAll(values: FloatArray) {
        if (values.size == 31) _gains.value = values.copyOf()
    }

    fun reset() { _gains.value = FloatArray(31) }
}
