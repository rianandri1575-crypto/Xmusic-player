package com.xmusic.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun configure(sampleRate: Double, freq: Double, gainDb: Double, q: Double = 1.0) {
        if (gainDb == 0.0 || freq <= 0.0 || freq >= sampleRate / 2.0) {
            setIdentity()
            return
        }
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val rb0 = 1 + alpha * A
        val rb1 = -2 * c
        val rb2 = 1 - alpha * A
        val ra0 = 1 + alpha / A
        val ra1 = -2 * c
        val ra2 = 1 - alpha / A
        b0 = rb0 / ra0; b1 = rb1 / ra0; b2 = rb2 / ra0
        a1 = ra1 / ra0; a2 = ra2 / ra0
    }

    fun configureLowPass(sampleRate: Double, freq: Double, q: Double) {
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val ra0 = 1 + alpha
        b0 = ((1 - c) / 2) / ra0
        b1 = (1 - c) / ra0
        b2 = ((1 - c) / 2) / ra0
        a1 = (-2 * c) / ra0
        a2 = (1 - alpha) / ra0
    }

    fun configureHighPass(sampleRate: Double, freq: Double, q: Double) {
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val ra0 = 1 + alpha
        b0 = ((1 + c) / 2) / ra0
        b1 = (-(1 + c)) / ra0
        b2 = ((1 + c) / 2) / ra0
        a1 = (-2 * c) / ra0
        a2 = (1 - alpha) / ra0
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    private fun setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }
}
