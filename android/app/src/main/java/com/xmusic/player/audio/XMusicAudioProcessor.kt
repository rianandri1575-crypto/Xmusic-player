package com.xmusic.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * XMusic's real-time PCM DSP chain.
 * 16-bit PCM -> 31 peaking EQ -> optional 3-band crossover/recombine -> limiter.
 * The crossover remains a stereo signal processor; it does not pretend to create
 * separate physical speaker outputs. External multi-output routing is device-dependent.
 */
class XMusicAudioProcessor : AudioProcessor {
    private var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var buffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false
    private var configured = false
    private val analyzerWindow = FloatArray(256)
    private var analyzerPos = 0
    private var analyzerCounter = 0
    private var sampleRate = 44100
    private var channels = 2

    private val filtersL = Array(31) { Biquad() }
    private val filtersR = Array(31) { Biquad() }

    // 3-band crossover banks: up to 4 cascaded 2nd-order stages per band per channel,
    // so 12/24/36/48 dB-per-octave slopes are implemented for real (Linkwitz-Riley Q=0.707).
    private val lowL = Array(4) { Biquad() }; private val lowR = Array(4) { Biquad() }
    private val highL = Array(4) { Biquad() }; private val highR = Array(4) { Biquad() }
    private val midLowL = Array(4) { Biquad() }; private val midLowR = Array(4) { Biquad() }
    private val midHighL = Array(4) { Biquad() }; private val midHighR = Array(4) { Biquad() }

    private val frequencies = doubleArrayOf(
        20.0,25.0,31.5,40.0,50.0,63.0,80.0,100.0,125.0,160.0,
        200.0,250.0,315.0,400.0,500.0,630.0,800.0,1000.0,1250.0,
        1600.0,2000.0,2500.0,3150.0,4000.0,5000.0,6300.0,8000.0,
        10000.0,12500.0,16000.0,20000.0
    )

    private var lastEq = FloatArray(31) { Float.NaN }
    private var lastCross = CrossoverConfig(enabled = false)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        outputAudioFormat = inputAudioFormat
        configured = true
        updateFilters(force = true)
        return outputAudioFormat
    }

    override fun isActive(): Boolean = configured

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        updateFilters(force = false)

        val bytes = inputBuffer.remaining()
        if (!buffer.hasRemaining() || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        buffer.clear()
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * (if (isFloat) 4 else 2)
        val crossoverConfig = CrossoverState.config.value
        val crossStages = (crossoverConfig.slopeDb / 12).coerceIn(1, 4)

        while (input.remaining() >= frameBytes) {
            var frameSum = 0.0
            for (ch in 0 until channels) {
                val x: Double
                if (isFloat) {
                    x = input.float.toDouble()
                } else {
                    val raw = input.short.toInt()
                    x = raw / 32768.0
                }
                val bank = if (ch == 0) filtersL else filtersR
                var y = x
                for (i in 0 until 31) y = bank[i].process(y)
                // DSP safety: if any filter diverges to NaN/Infinity, pass the raw
                // sample through instead of letting bad state poison the whole chain.
                if (!y.isFinite()) y = x

                if (crossoverConfig.enabled) {
                    y = processCrossover(y, ch, crossoverConfig, crossStages)
                }

                // Transparent below 0 dBFS; only engage the soft limiter on overload.
                val limited = if (abs(y) <= 1.0) y else tanh(y * 0.95) / tanh(0.95)
                frameSum += limited
                if (isFloat) {
                    buffer.putFloat(limited.toFloat())
                } else {
                    val outInt = (limited.coerceIn(-1.0, 1.0) * 32767.0).toInt()
                    buffer.putShort(outInt.toShort())
                }
            }
            feedAnalyzer((frameSum / channels).toFloat())
        }
        inputBuffer.position(input.position())
        buffer.flip()
    }

    private fun processCrossover(x: Double, ch: Int, c: CrossoverConfig, stages: Int): Double {
        var low = x
        var mid = x
        var high = x
        for (i in 0 until stages) {
            low = (if (ch == 0) lowL[i] else lowR[i]).process(low)
            mid = (if (ch == 0) midLowL[i] else midLowR[i]).process(mid)
            high = (if (ch == 0) highL[i] else highR[i]).process(high)
        }
        for (i in 0 until stages) {
            mid = (if (ch == 0) midHighL[i] else midHighR[i]).process(mid)
        }
        return low * db(c.lowGainDb) + mid * db(c.midGainDb) + high * db(c.highGainDb)
    }


    private fun feedAnalyzer(sample: Float) {
        analyzerWindow[analyzerPos++] = sample
        if (analyzerPos < analyzerWindow.size) return
        analyzerPos = 0
        analyzerCounter++
        if (analyzerCounter % 2 != 0) return
        val bins = FloatArray(32)
        val centers = doubleArrayOf(31.0,45.0,63.0,90.0,125.0,180.0,250.0,355.0,500.0,710.0,1000.0,1415.0,2000.0,2830.0,4000.0,5660.0,8000.0,11300.0,16000.0,20000.0,22000.0,24000.0,26000.0,28000.0,30000.0,32000.0,34000.0,36000.0,38000.0,40000.0,42000.0,44000.0)
        val n = analyzerWindow.size
        val sr = sampleRate.toDouble().coerceAtLeast(8000.0)
        for (b in bins.indices) {
            val f = centers[b].coerceAtMost(sr * 0.45)
            val omega = 2.0 * kotlin.math.PI * f / sr
            val coeff = 2.0 * kotlin.math.cos(omega)
            var q1 = 0.0
            var q2 = 0.0
            for (i in 0 until n) {
                val q0 = coeff * q1 - q2 + analyzerWindow[i]
                q2 = q1; q1 = q0
            }
            val power = (q1*q1 + q2*q2 - coeff*q1*q2).coerceAtLeast(0.0) / (n*n)
            bins[b] = kotlin.math.sqrt(power).toFloat().coerceIn(0f, 1f)
        }
        val peak = analyzerWindow.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val db = (20.0 * kotlin.math.log10(peak.toDouble())).toFloat().coerceAtLeast(-60f)
        AnalyzerState.publish(bins, db, db)
    }

    override fun queueEndOfStream() { ended = true }

    override fun getOutput(): ByteBuffer {
        val out = buffer
        buffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = ended && !buffer.hasRemaining()

    override fun flush() {
        buffer = AudioProcessor.EMPTY_BUFFER
        ended = false
        filtersL.forEach { it.reset() }; filtersR.forEach { it.reset() }
        listOf(lowL, lowR, highL, highR, midLowL, midLowR, midHighL, midHighR).forEach { bank -> bank.forEach { it.reset() } }
        if (configured) updateFilters(force = true)
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        configured = false
        analyzerPos = 0
        analyzerCounter = 0
    }

    private fun updateFilters(force: Boolean) {
        val gains = EqualizerState.gains.value
        val cross = CrossoverState.config.value
        if (force || !gains.contentEquals(lastEq)) {
            for (i in 0 until 31) {
                filtersL[i].configure(sampleRate.toDouble(), frequencies[i], gains[i].toDouble(), 1.0)
                filtersR[i].configure(sampleRate.toDouble(), frequencies[i], gains[i].toDouble(), 1.0)
            }
            lastEq = gains.copyOf()
        }
        if (force || cross != lastCross) {
            configureCrossover(cross)
            lastCross = cross
        }
    }

    private fun configureCrossover(c: CrossoverConfig) {
        val sr = sampleRate.toDouble().coerceAtLeast(8000.0)
        val stages = (c.slopeDb / 12).coerceIn(1, 4)
        // Linkwitz-Riley: cascaded 2nd-order Butterworth stages (Q = 1/√2) so the bands
        // sum back to a flat/transparent response when all gains are 0 dB.
        val q = 0.7071
        val banks = listOf(lowL, lowR, highL, highR, midLowL, midLowR, midHighL, midHighR)
        banks.forEach { bank -> bank.forEach { it.reset() } }
        if (!c.enabled) {
            // Bypass: identity transfer through every stage.
            banks.forEach { bank -> bank.forEach { it.configure(sr, 1000.0, 0.0, 0.707) } }
            return
        }
        val lowHz = c.lowHz.toDouble().coerceIn(20.0, sr / 2.0 - 100.0)
        val highHz = c.highHz.toDouble().coerceIn(lowHz + 20.0, sr / 2.0 - 50.0)
        for (i in 0 until 4) {
            if (i < stages) {
                lowL[i].configureLowPass(sr, lowHz, q); lowR[i].configureLowPass(sr, lowHz, q)
                midLowL[i].configureHighPass(sr, lowHz, q); midLowR[i].configureHighPass(sr, lowHz, q)
                midHighL[i].configureLowPass(sr, highHz, q); midHighR[i].configureLowPass(sr, highHz, q)
                highL[i].configureHighPass(sr, highHz, q); highR[i].configureHighPass(sr, highHz, q)
            } else {
                // Unused stages must be transparent; otherwise stale coefficients
                // from a previous higher-slope setting would keep filtering.
                lowL[i].configure(sr, 1000.0, 0.0, 0.707); lowR[i].configure(sr, 1000.0, 0.0, 0.707)
                midLowL[i].configure(sr, 1000.0, 0.0, 0.707); midLowR[i].configure(sr, 1000.0, 0.0, 0.707)
                midHighL[i].configure(sr, 1000.0, 0.0, 0.707); midHighR[i].configure(sr, 1000.0, 0.0, 0.707)
                highL[i].configure(sr, 1000.0, 0.0, 0.707); highR[i].configure(sr, 1000.0, 0.0, 0.707)
            }
        }
    }

    private fun db(gain: Float): Double = 10.0.pow(gain / 20.0)
}

private fun Double.pow(exp: Double): Double = kotlin.math.exp(exp * kotlin.math.ln(this))
