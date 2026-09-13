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
    private val lowL = Biquad(); private val lowR = Biquad()
    private val highL = Biquad(); private val highR = Biquad()
    private val midLowL = Biquad(); private val midLowR = Biquad()
    private val midHighL = Biquad(); private val midHighR = Biquad()

    private val frequencies = doubleArrayOf(
        20.0,25.0,31.5,40.0,50.0,63.0,80.0,100.0,125.0,160.0,
        200.0,250.0,315.0,400.0,500.0,630.0,800.0,1000.0,1250.0,
        1600.0,2000.0,2500.0,3150.0,4000.0,5000.0,6300.0,8000.0,
        10000.0,12500.0,16000.0,20000.0
    )

    private var lastEq = FloatArray(31) { Float.NaN }
    private var lastCross = CrossoverConfig(enabled = false)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
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
        val frameBytes = channels * 2

        while (input.remaining() >= frameBytes) {
            for (ch in 0 until channels) {
                val raw = input.short.toInt()
                val x = raw / 32768.0
                val bank = if (ch == 0) filtersL else filtersR
                var y = x
                for (i in 0 until 31) y = bank[i].process(y)
                // DSP safety: if any filter diverges to NaN/Infinity, pass the raw
                // sample through instead of letting bad state poison the whole chain.
                if (!y.isFinite()) y = x

                val c = CrossoverState.config.value
                if (c.enabled) {
                    y = processCrossover(y, ch, c)
                }

                // Transparent below 0 dBFS; only engage the soft limiter on overload.
                // This avoids changing every sample when EQ/crossover are flat.
                val limited = if (abs(y) <= 1.0) y else tanh(y * 0.95) / tanh(0.95)
                if (ch == 0) feedAnalyzer(limited.toFloat())
                val out = (limited.coerceIn(-1.0, 1.0) * 32767.0).toInt()
                buffer.putShort(out.toShort())
            }
        }
        inputBuffer.position(input.position())
        buffer.flip()
    }

    private fun processCrossover(x: Double, ch: Int, c: CrossoverConfig): Double {
        val low = if (ch == 0) lowL.process(x) else lowR.process(x)
        val hpAtLow = if (ch == 0) midLowL.process(x) else midLowR.process(x)
        val high = if (ch == 0) highL.process(x) else highR.process(x)
        val mid = if (ch == 0) midHighL.process(hpAtLow) else midHighR.process(hpAtLow)
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
        listOf(lowL, lowR, highL, highR, midLowL, midLowR, midHighL, midHighR).forEach { it.reset() }
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
        if (!c.enabled) {
            listOf(lowL, lowR, highL, highR, midLowL, midLowR, midHighL, midHighR).forEach { it.configure(sampleRate.toDouble(), 1000.0, 0.707, 0.0) }
            return
        }
        val q = when (c.slopeDb) { 12 -> 0.707; 24 -> 0.5412; 36 -> 0.4595; else -> 0.42 }
        val lowHz = c.lowHz.toDouble().coerceIn(20.0, sampleRate / 2.0 - 100.0)
        val highHz = c.highHz.toDouble().coerceIn(lowHz + 20.0, sampleRate / 2.0 - 50.0)
        lowL.configureLowPass(sampleRate.toDouble(), lowHz, q); lowR.configureLowPass(sampleRate.toDouble(), lowHz, q)
        midLowL.configureHighPass(sampleRate.toDouble(), lowHz, q); midLowR.configureHighPass(sampleRate.toDouble(), lowHz, q)
        midHighL.configureLowPass(sampleRate.toDouble(), highHz, q); midHighR.configureLowPass(sampleRate.toDouble(), highHz, q)
        highL.configureHighPass(sampleRate.toDouble(), highHz, q); highR.configureHighPass(sampleRate.toDouble(), highHz, q)
    }

    private fun db(gain: Float): Double = 10.0.pow(gain / 20.0)
}

private fun Double.pow(exp: Double): Double = kotlin.math.exp(exp * kotlin.math.ln(this))
