package com.xmusic.player.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CrossoverConfig(
    val enabled: Boolean = true,
    val lowHz: Float = 80f,
    val highHz: Float = 2500f,
    val slopeDb: Int = 24,
    val lowGainDb: Float = 0f,
    val midGainDb: Float = 0f,
    val highGainDb: Float = 0f
)

object CrossoverState {
    private val _config = MutableStateFlow(CrossoverConfig())
    val config = _config.asStateFlow()

    fun update(config: CrossoverConfig) { _config.value = config }
    fun reset() { _config.value = CrossoverConfig() }
}
