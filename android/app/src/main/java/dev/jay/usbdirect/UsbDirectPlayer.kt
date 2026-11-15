// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Expected producer exit after stop/seek, not a detach. */
private class PlaybackAborted : RuntimeException()

/**
 * Decodes a file to PCM and streams it into the raw-usbfs engine.
 *
 * Decoding is MediaExtractor + MediaCodec (AAC/FLAC/Opus/MP3/...); plain PCM
 * tracks are read straight from the extractor. Everything is converted to
 * float, resampled to the DAC rate if needed, and handed to native.
 */
class UsbDirectPlayer(private val context: Context) {

    companion object {
        private val SUPPORTED_RATES = setOf(44100, 48000, 96000)
    }


    var onError: ((String) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    var onProgress: ((positionMs: Long) -> Unit)? = null
    var onFormat: ((rate: Int, bits: Int) -> Unit)? = null
    var onMissing: ((Uri) -> Unit)? = null
    /** Next track for seamless continuation, or null to end normally. */
    var gaplessProvider: (() -> Pair<Int, Track>?)? = null
    /** Fires once the new track is actually reaching the DAC. */
    var onBoundary: ((Int) -> Unit)? = null

    @Volatile private var mixerVolume = 0.2f
    @Volatile private var running = false
    @Volatile private var session = 0
    /** Last position reported from reaped URBs; used to snapshot on DAC detach. */
    @Volatile private var lastReportedPosition = 0L
    /** libsamplerate converter quality for resampled rates. */
    @Volatile var resamplerQuality: Int = NativeResampler.QUALITY_MEDIUM
    /** TPDF dither in the native packer. */
    @Volatile private var dither = true
    /** 0 = match each track's source depth, 16/24 = fixed output depth. */
    @Volatile private var autoBitDepth = true
    @Volatile var fixedBits = 24
    @Volatile private var currentOutBits = 24
    /** DSP rack state survives engine restarts and track changes. */
    @Volatile private var dspConfig = DspConfig()
    @Volatile private var dspEnabled = false
    /** Second rack owned by the mixer sink; null while no mixer sink is live. */
    @Volatile private var mixerDsp: MixerDsp? = null
    private val mixerDspLock = Any()
    /** True when PcmSink is using libsamplerate for this stream. */
    @Volatile private var resampled = false
    /** Decoded source depth; unknown/float sources are treated as 24-bit. */
    @Volatile private var sourceBits = 24
    @Volatile private var keepEngineForReuse = false
    private var activeDeviceId = -1
    private var thread: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var engine: UsbAudioEngine? = null

    /** Diagnostics: null when no engine is open (paused or idle). */
    fun telemetry(): LongArray = engine?.telemetry() ?: LongArray(0)
    fun summary(): String = engine?.summary() ?: ""
    fun hwVolumeInfo(): IntArray? = engine?.hwVolumeInfo()
    fun setHwVolume(raw: Int): Boolean = engine?.setHwVolume(raw) ?: false

    /** Analyser taps the post-volume float stream, before dither/packing. */
    val analyzer = AudioAnalyzer()

    val isRunning: Boolean get() = running

    fun start(device: UsbDevice?, uri: Uri, rate: Int, bits: Int, volume: Float,
              startMs: Long = 0L, useMixer: Boolean = false, sourceBits: Int = 24) {
        // Play at the file's own rate when the DAC supports it, so nothing is
        // resampled; otherwise fall back to the requested rate.
        lastReportedPosition = startMs
        currentOutBits = bits
        this.sourceBits = if (sourceBits == 16) 16 else 24
        val src = probeRate(uri)
        val dev = if (src in SUPPORTED_RATES) src else rate
        resampled = src != dev
        onFormat?.invoke(dev, bits)
        Log.i("VCProbe", "source=" + src + " Hz -> device=" + dev + " Hz" +
                if (src == dev) " (bit-perfect path, no resample)" else " (resampled)")
        if (useMixer) {
            startMixer(device, uri, dev, volume, startMs)
        } else {
            // exclusive path always has a device; mixer mode may run without one
            startInternal(device!!, dev, bits, volume) { eng ->
                decodeLoop(uri, UsbTarget(eng), dev, startMs)
            }
        }
    }

    /**
     * Fallback path: let Android's mixer own the DAC. Not bit-perfect and the
     * rate is whatever the HAL picks, but it mixes with other apps and needs no
     * exclusive access.
     */
    private fun startMixer(device: UsbDevice?, uri: Uri, rate: Int, volume: Float, startMs: Long) {
        stop()
        val gen = ++session
        mixerVolume = volume
        running = true
        thread = Thread {
            var track: AudioTrack? = null
            var eq: MixerDsp? = null
            try {
                // Mixer/headphone EQ lives with the sink. A fresh instance per
                // start gives clean filter state and coefficients for this rate.
                val d = MixerDsp(rate)
                eq = d
                synchronized(mixerDspLock) {
                    mixerDsp?.close()
                    mixerDsp = d
                    d.setConfig(dspConfig)
                    d.setEnabled(dspEnabled)
                }
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                // Prefer the USB DAC when one is present; otherwise let the
                // platform pick (speaker, Bluetooth, wired) so the app is still
                // usable without a DAC plugged in.
                val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }

                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(fmt)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(rate / 10 * 8)
                    .build()
                if (dev != null) track.setPreferredDevice(dev)
                track.play()
                analyzer.start()
                Log.i("VCProbe", "mixer sink open: " + rate + " Hz")
                onInfo?.invoke("mixer: ${rate} Hz")
                decodeLoop(uri, MixerTarget(track, { mixerVolume }, d), rate, startMs)
            } catch (_: PlaybackAborted) {
                // seek/stop expected this writer to leave
            } catch (t: Throwable) {
                Log.e("VCProbe", "mixer failed: ${t.message}", t)
                if (running) onError?.invoke(t.message ?: t.toString())
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                synchronized(mixerDspLock) {
                    if (mixerDsp === eq) mixerDsp = null
                }
                eq?.close()
                running = false
                if (gen == session) onFinished?.invoke()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun probeRate(uri: Uri): Int {
        val ex = MediaExtractor()
        return try {
            if (uri.scheme == "file") ex.setDataSource(uri.path!!)
            else context.contentResolver.openFileDescriptor(uri, "r")!!.use {
                ex.setDataSource(it.fileDescriptor)
            }
            var r = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) { r = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); break }
            }
            r
        } catch (t: Throwable) {
            Log.e("VCProbe", "probeRate failed: " + t.message)
            0
        } finally {
            ex.release()
        }
    }

    private fun sourceBitsFor(t: Track): Int =
        if (t.bitDepth == 16) 16 else 24

    private fun probeChannels(uri: Uri): Int {
        val ex = MediaExtractor()
        return try {
            if (uri.scheme == "file") ex.setDataSource(uri.path!!)
            else context.contentResolver.openFileDescriptor(uri, "r")!!.use {
                ex.setDataSource(it.fileDescriptor)
            }
            var c = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) { c = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); break }
            }
            c
        } catch (t: Throwable) { 0 } finally { ex.release() }
    }

    /** Plays a 1 kHz tone straight from native, no decoding involved. */
    fun startTone(device: UsbDevice, rate: Int, bits: Int, volume: Float, seconds: Int) {
        resampled = false
        sourceBits = 24
        startInternal(device, rate, bits, volume) { eng -> playTone(eng, rate, seconds) }
    }

    private fun startInternal(device: UsbDevice, rate: Int, bits: Int, volume: Float,
                              body: (UsbAudioEngine) -> Unit) {
        // Fast path: same DAC, interface still ours. Stop the OLD decode thread
        // first — on a manual skip it is still running, and starting a second
        // one against the same engine mixed two tracks together.
        val live = engine
        if (live != null && connection != null && device.deviceId == activeDeviceId) {
            keepEngineForReuse = true
            val gen = ++session          // invalidate the old thread's callbacks
            running = false
            live.abort()                 // wake a writer blocked inside uae_write
            val old = thread
            thread = null
            var oldAlive = false
            if (old != null && old !== Thread.currentThread()) {
                old.interrupt()
                try { old.join(2000) } catch (_: InterruptedException) { }
                oldAlive = old.isAlive
            }
            keepEngineForReuse = false

            if (!oldAlive) {
                live.resume()
                if (live.switchFormat(rate, bits) == 0) {
                    Log.i("VCProbe", "reused engine -> $rate Hz / $bits-bit (no teardown)")
                    // switch_format recompiles coefficients for the new rate; push
                    // the Kotlin-side config again so the two never drift.
                    live.setDspConfig(dspConfig)
                    live.setDspEnabled(dspEnabled)
                    live.setResampled(resampled)
                    live.setSourceBits(sourceBits)
                    running = true
                    thread = Thread {
                        var completed = false
                        try {
                            analyzer.start()
                            live.setVolume(volume)
                            live.setDither(dither)
                            body(live)
                            completed = true
                        } catch (_: PlaybackAborted) {
                            // seek/stop expected this writer to leave
                        } catch (t: Throwable) {
                            Log.e("VCProbe", "decode failed: ${t.message}", t)
                            if (running) onError?.invoke(t.message ?: t.toString())
                        } finally {
                            if (!keepEngineForReuse && (!completed || !running)) closeEngine()
                            running = false
                            if (gen == session) onFinished?.invoke()
                        }
                    }.also { it.isDaemon = true; it.start() }
                    return
                }
            }
            closeEngine()
        }

        closeEngine()
        val gen = ++session
        running = true
        thread = Thread {
            var conn: UsbDeviceConnection? = null
            var eng: UsbAudioEngine? = null
            var completed = false
            try {
                val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
                conn = mgr.openDevice(device)
                    ?: throw IllegalStateException("openDevice failed - USB permission?")
                connection = conn
                activeDeviceId = device.deviceId

                eng = UsbAudioEngine.open(conn.fileDescriptor, rate, bits)
                if (!eng.isOk) {
                    val msg = eng.error
                    eng.close()
                    throw IllegalStateException(msg)
                }
                engine = eng
                eng.setVolume(volume)
                eng.setDither(dither)
                eng.setDspConfig(dspConfig)
                eng.setDspEnabled(dspEnabled)
                eng.setResampled(resampled)
                eng.setSourceBits(sourceBits)

                analyzer.start()
                Log.i("VCProbe", "engine open ok: " + rate + " Hz / " + bits + "-bit")
                onInfo?.invoke("engine: ${rate} Hz / $bits-bit")
                body(eng)
                completed = true
            } catch (_: PlaybackAborted) {
                // seek/stop expected this writer to leave
            } catch (t: Throwable) {
                Log.e("VCProbe", "engine failed: ${t.message}", t)
                if (running) onError?.invoke(t.message ?: t.toString())
            } finally {
                if (!keepEngineForReuse && (!completed || !running)) {
                    eng?.close()
                    if (engine === eng) engine = null
                    conn?.close()
                    if (connection === conn) connection = null
                    activeDeviceId = -1
                }
                running = false
                if (gen == session) onFinished?.invoke()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun closeEngine() {
        val eng = engine
        engine = null
        eng?.abort()        // never take the write guard behind a blocked writer
        eng?.close()
        connection?.close()
        connection = null
        activeDeviceId = -1
    }

    fun stop() {
        session++            // invalidate any in-flight completion callback
        analyzer.stop()
        running = false
        engine?.abort()      // writers see aborting and leave uae_write promptly

        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            try {
                t.join(1500)
                if (t.isAlive) Log.w("VCProbe", "stop: join timed out")
            } catch (_: InterruptedException) { }
        }

        closeEngine()
    }

    /** Audible position of the current track, from reaped URBs. */
    fun positionMs(): Long = lastReportedPosition

    /** Output depth policy: auto (match source) or a fixed 16/24. */
    fun setBitDepthPolicy(auto: Boolean, bits: Int) {
        autoBitDepth = auto
        fixedBits = bits
    }

    /** Depth this track will be packed at, used by the gapless look-ahead. */
    fun outputBitsFor(t: Track): Int {
        if (!autoBitDepth) return fixedBits
        if (t.bitDepth == 16 || t.bitDepth == 24) return t.bitDepth
        val probed = MediaProbe.of(context, t.uri)?.bitDepth ?: 0
        return if (probed == 16 || probed == 24) probed else 24
    }

    fun setDither(on: Boolean) {
        dither = on
        engine?.setDither(on)
    }

    /** Store the rack config even when no engine is open. */
    fun setDspConfig(config: DspConfig) {
        dspConfig = config
        engine?.setDspConfig(config)
        synchronized(mixerDspLock) { mixerDsp?.setConfig(config) }
    }

    fun setDspEnabled(on: Boolean) {
        dspEnabled = on
        engine?.setDspEnabled(on)
        synchronized(mixerDspLock) { mixerDsp?.setEnabled(on) }
    }

    fun resetDsp() {
        engine?.resetDsp()
        synchronized(mixerDspLock) { mixerDsp?.reset() }
    }

    fun setVolume(v: Float) {
        mixerVolume = v
        engine?.setVolume(v)
    }
    fun setPpm(ppm: Float) { engine?.setPpm(ppm) }

    private fun playTone(eng: UsbAudioEngine, rate: Int, seconds: Int) {
        val chunk = 1024
        val buf = ByteBuffer.allocateDirect(chunk * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val total = rate.toLong() * seconds
        var written = 0L
        var phase = 0.0
        val step = 2.0 * Math.PI * 1000.0 / rate
        while (running && written < total) {
            buf.clear()
            for (i in 0 until chunk) {
                val v = (0.25 * kotlin.math.sin(phase)).toFloat()
                phase += step
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                buf.put(v); buf.put(v)
            }
            buf.flip()
            if (eng.write(buf, chunk) < 0) break
            written += chunk
            if (written % (rate.toLong() * 2) == 0L)
                Log.i("VCProbe", "tone: ${written / rate} s written")
        }
    }

    // ------------------------------------------------------------- decoding

    private fun decodeLoop(uri: Uri, target: PcmTarget, outRate: Int, startMs: Long) {
        Log.i("VCProbe", "decode begin")
        analyzer.setSampleRate(outRate)
        val myGen = session

        var currentUri = uri
        var currentStart = startMs
        var sink: PcmSink? = null

        while (running && session == myGen) {
            val extractor = MediaExtractor()
            try {
                if (currentUri.scheme == "file") {
                    extractor.setDataSource(currentUri.path!!)
                } else {
                    context.contentResolver.openFileDescriptor(currentUri, "r")!!.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                }
            } catch (t: Throwable) {
                Log.w("VCProbe", "cannot open $currentUri: ${t.message}")
                runCatching { extractor.release() }
                onMissing?.invoke(currentUri)
                break
            }

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) {
                extractor.release()
                throw IllegalStateException("no audio track")
            }
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            onInfo?.invoke("$mime  $srcRate Hz  ${srcCh}ch  ->  $outRate Hz")
            Log.i("VCProbe", "track mime=$mime rate=$srcRate ch=$srcCh")

            if (sink == null) {
                sink = PcmSink(target, srcRate, outRate, srcCh, currentStart,
                    sessionAtStart = myGen,
                    currentSession = { session },
                    progress = { ms -> lastReportedPosition = ms; onProgress?.invoke(ms) },
                    tap = { b, n ->
                        analyzer.delayFrames = target.bufferedFrames()
                        analyzer.offer(b, n, mixerVolume)
                    },
                    onBoundary = { idx -> if (running && session == myGen) onBoundary?.invoke(idx) },
                    quality = resamplerQuality)
            }
            if (currentStart > 0) {
                extractor.seekTo(currentStart * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                Log.i("VCProbe", "seek to $currentStart ms (sampleTime=${extractor.sampleTime})")
            }

            val s = sink
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) decodeRaw(extractor, s, format)
            else decodeCodec(extractor, format, track, s)
            extractor.release()

            if (!running || session != myGen) break

            // Natural end: continue seamlessly when the next track has the same
            // native format. Anything else falls back to the normal end path.
            val next = gaplessProvider?.invoke() ?: break
            val (nextIndex, nextTrack) = next
            if (srcRate != outRate || srcCh != s.channels()) break
            val nextBits = outputBitsFor(nextTrack)
            if (nextBits != currentOutBits) {
                Log.i("VCProbe", "gapless: bit depth $currentOutBits -> $nextBits, normal switch")
                break
            }
            val nextRate = probeRate(nextTrack.uri)
            val nextCh = probeChannels(nextTrack.uri)
            if (nextRate != outRate || nextCh != srcCh) {
                Log.i("VCProbe", "gapless: format change $nextRate/${nextCh}ch -> normal switch")
                break
            }

            s.commitBoundary(nextIndex, sourceBitsFor(nextTrack))
            Log.i("VCProbe", "gapless: continuing into ${nextTrack.title}")
            currentUri = nextTrack.uri
            currentStart = 0
        }

        sink?.flush()
        target.eos()
        Log.i("VCProbe", "decode end")
    }

    private fun decodeRaw(extractor: MediaExtractor, sink: PcmSink, format: MediaFormat) {
        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2 /* PCM_16BIT */
        sink.setSourceBits(
            if (pcmEncoding == 4 || pcmEncoding == 21) 24 else 16
        )

        val buf = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())
        var reads = 0
        var total = 0L
        while (running) {
            buf.clear()
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) { Log.i("VCProbe", "raw EOF after " + reads + " reads, " + total + " bytes"); break }
            reads++
            total += n
            if (reads % 50 == 0) Log.i("VCProbe", "raw reads=" + reads + " bytes=" + total + " enc=" + pcmEncoding)
            buf.position(0); buf.limit(n)
            when (pcmEncoding) {
                4 -> sink.pushFloat(buf.asFloatBuffer())      // PCM_FLOAT
                21 -> sink.push24(buf)                        // PCM_24BIT_PACKED
                else -> sink.pushShorts(buf.asShortBuffer())  // PCM_16BIT
            }
            extractor.advance()
        }
    }

    private fun decodeCodec(extractor: MediaExtractor, format: MediaFormat,
                            track: Int, sink: PcmSink) {
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (running && !outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(ib, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    if (info.size > 0) {
                        val enc = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                        if (enc == 4 || enc == 21) sink.setSourceBits(24)
                        when (enc) {
                            4 -> sink.pushFloat(ob.asFloatBuffer())
                            21 -> sink.push24(ob)
                            else -> sink.pushShorts(ob.asShortBuffer())
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onInfo?.invoke("decoder format changed")
                }
            }
        }
        codec.stop()
        codec.release()
    }

    /**
     * Interleaved source frames -> stereo float at the DAC rate, linear
     * interpolated. v1 quality: fine for equal rates, adequate otherwise.
     * TODO: swap in libsamplerate for arbitrary ratios.
     */
    internal interface PcmTarget {
        fun write(buf: FloatBuffer, bytes: ByteBuffer, frames: Int): Int
        fun eos()
        /** Frames actually consumed by the sink, not frames handed to it. */
        fun playedFrames(): Long
        /** Frames buffered between us and the listener. */
        fun bufferedFrames(): Long
        /** Decoded source depth for dither/badge decisions. */
        fun setSourceBits(bits: Int) {}
        /** True after stop/seek/close; a write failure is expected, not detach. */
        fun isAborting(): Boolean = false
        /** Clear filter history at a gapless track boundary (mixer only). */
        fun resetDsp() {}
    }

    private class UsbTarget(private val eng: UsbAudioEngine) : PcmTarget {
        override fun write(buf: FloatBuffer, bytes: ByteBuffer, frames: Int) =
            eng.write(buf, frames)
        override fun eos() = eng.eos()
        override fun playedFrames(): Long = eng.framesPlayed()
        override fun bufferedFrames(): Long = eng.bufferedFrames()
        override fun setSourceBits(bits: Int) = eng.setSourceBits(bits)
        override fun isAborting(): Boolean = eng.isAborting
    }

    private class MixerTarget(
        private val track: AudioTrack,
        private val volumeProvider: () -> Float,
        private val dsp: MixerDsp
    ) : PcmTarget {
        override fun write(buf: FloatBuffer, bytes: ByteBuffer, frames: Int): Int {
            val v = volumeProvider()
            if (v < 0.999f) {
                val lim = buf.limit()
                for (i in 0 until lim) buf.put(i, buf.get(i) * v)
            }
            // Mixer EQ runs on this writer thread, on the float block, directly
            // before the sink. When bypassed engaged is false: no native call,
            // no buffer copy, no DSP code on the sample path.
            if (dsp.engaged) dsp.process(buf, frames)
            bytes.position(0)
            bytes.limit(frames * 8)          // 2 ch * 4 bytes
            val written = track.write(bytes, frames * 8, AudioTrack.WRITE_BLOCKING)
            return if (written <= 0) written else written / 8   // bytes -> frames
        }
        override fun eos() { /* nothing queued to flush */ }
        override fun playedFrames(): Long =
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        override fun bufferedFrames(): Long = 0L
        override fun resetDsp() = dsp.reset()
    }

    internal class PcmSink(
        private val target: PcmTarget,
        private val srcRate: Int,
        private val outRate: Int,
        private val srcCh: Int,
        private val startMs: Long,
        private val sessionAtStart: Int,
        private val currentSession: () -> Int,
        private val progress: ((Long) -> Unit)?,
        private val tap: ((FloatBuffer, Int) -> Unit)?,
        private val onBoundary: ((Int) -> Unit)?,
        quality: Int = NativeResampler.QUALITY_MEDIUM
    ) {
        private companion object {
            const val BLOCK = 2048              // frames per resampler pass
            const val OUT_MAX = BLOCK * 3       // generous headroom above any ratio
        }

        private val passthrough = srcRate == outRate
        private val resampler =
            if (passthrough) null
            else NativeResampler(2, outRate.toDouble() / srcRate, quality)

        private val outBytes: ByteBuffer =
            ByteBuffer.allocateDirect(OUT_MAX * 2 * 4).order(ByteOrder.nativeOrder())
        private val outBuf: FloatBuffer = outBytes.asFloatBuffer()

        private val inBytes: ByteBuffer =
            ByteBuffer.allocateDirect(BLOCK * 2 * 4).order(ByteOrder.nativeOrder())
        private val inBuf: FloatBuffer = inBytes.asFloatBuffer()

        private val stage = FloatArray(BLOCK * 2)
        private var stageFrames = 0

        private var lastReportMs = 0L
        private var framesOut = 0L
        private var lastPlayedMs = 0L
        private var boundaryFrame = 0L
        private var pendingBoundary = -1
        private var pendingBoundaryBits = 24
        private var trackStartFrames = 0L

        init {
            // linear interpolation is gone: equal rates pass through untouched
            if (resampler != null && !resampler.isOk) {
                android.util.Log.e("VCProbe", "resampler init failed")
            }
        }

        fun pushShorts(buf: java.nio.ShortBuffer) {
            val frames = buf.remaining() / srcCh
            for (i in 0 until frames) {
                val l = buf.get().toFloat() / 32768f
                val r = if (srcCh >= 2) buf.get().toFloat() / 32768f else l
                for (k in 2 until srcCh) buf.get()
                emit(l, r)
            }
        }

        fun push24(buf: ByteBuffer) {
            val frames = buf.remaining() / (3 * srcCh)
            for (i in 0 until frames) {
                val l = read24(buf)
                val r = if (srcCh >= 2) read24(buf) else l
                for (k in 2 until srcCh) buf.position(buf.position() + 3)
                emit(l, r)
            }
        }

        fun pushFloat(buf: FloatBuffer) {
            val frames = buf.remaining() / srcCh
            for (i in 0 until frames) {
                val l = buf.get()
                val r = if (srcCh >= 2) buf.get() else l
                for (k in 2 until srcCh) buf.get()
                emit(l, r)
            }
        }

        private fun read24(b: ByteBuffer): Float {
            val b0 = b.get().toInt() and 0xFF
            val b1 = b.get().toInt() and 0xFF
            val b2 = b.get().toInt()
            var v = (b2 shl 16) or (b1 shl 8) or b0
            if (v and 0x800000 != 0) v = v or -0x1000000
            return v / 8388608f
        }

        private fun emit(l: Float, r: Float) {
            if (passthrough) {
                outBuf.put(l); outBuf.put(r)
                if (outBuf.position() >= OUT_MAX * 2) flushOut()
            } else {
                stage[stageFrames * 2] = l
                stage[stageFrames * 2 + 1] = r
                stageFrames++
                if (stageFrames == BLOCK) processBlock()
            }
        }

        private fun processBlock() {
            val rs = resampler ?: return
            inBuf.clear()
            inBuf.put(stage, 0, stageFrames * 2)
            stageFrames = 0
            outBuf.clear()
            val produced = rs.process(inBuf, BLOCK, outBuf, OUT_MAX)
            if (produced > 0) flushOut(produced)
        }

        private fun flushOut(frames: Int = outBuf.position() / 2) {
            if (frames <= 0) return
            // libsamplerate writes into the direct buffer without moving the
            // FloatBuffer position, so set the window explicitly.
            outBuf.position(0)
            outBuf.limit(frames * 2)
            tap?.invoke(outBuf, frames)
            var wrote: Int
            var resets = 0
            while (true) {
                wrote = target.write(outBuf, outBytes, frames)
                if (wrote > 0) {
                    if (sessionAtStart != currentSession() || target.isAborting()) {
                        outBuf.clear()
                        throw PlaybackAborted()
                    }
                    break
                }

                val stale = sessionAtStart != currentSession()
                val aborting = target.isAborting()
                if (wrote == UsbAudioEngine.ERR_RESET && !stale && !aborting) {
                    // The ring was cleared under a live session (seek/reset).
                    // Rewrite the same block in the new epoch.
                    if (++resets <= 8) {
                        Thread.yield()
                        continue
                    }
                    outBuf.clear()
                    throw PlaybackAborted()
                }
                if (wrote == UsbAudioEngine.ERR_CLOSING ||
                    wrote == UsbAudioEngine.ERR_RESET || stale || aborting) {
                    // stop()/seek()/close() asked the producer to leave. This is
                    // not a detach and must not advance the queue or fall back.
                    outBuf.clear()
                    throw PlaybackAborted()
                }

                // Genuine USB failure with the same session still current: the
                // DAC vanished. Keep framesOut unadvanced so the service can
                // pause at the last confirmed frame and fall back to the mixer.
                outBuf.clear()
                throw IllegalStateException("sink write failed ($wrote/$frames)")
            }
            // Partial blocks (gapless commitBoundary, seek flush, stop flush)
            // shrink the window above. Restore the full-capacity state so the
            // next track's relative emit() cannot run past the shrunk limit.
            outBuf.clear()
            framesOut += frames

            val played = target.playedFrames()
            if (pendingBoundary >= 0 && played >= boundaryFrame) {
                trackStartFrames = boundaryFrame
                val idx = pendingBoundary
                pendingBoundary = -1
                target.setSourceBits(pendingBoundaryBits)
                target.resetDsp()         // new track, clean mixer filter state
                onBoundary?.invoke(idx)     // metadata flips on the audible sample
            }
            val rel = played - trackStartFrames
            val base = if (trackStartFrames > 0) 0L else startMs
            val ms = base + rel * 1000L / outRate
            lastPlayedMs = ms
            if (ms - lastReportMs >= 200 || ms < lastReportMs) {
                lastReportMs = ms
                progress?.invoke(ms)
            }
        }

        fun channels(): Int = srcCh

        fun setSourceBits(bits: Int) = target.setSourceBits(bits)

        /** Flush what is queued, then arm the audible switch at this frame. */
        fun commitBoundary(index: Int, sourceBits: Int) {
            flushOut()
            boundaryFrame = framesOut
            pendingBoundary = index
            pendingBoundaryBits = sourceBits
        }

        fun flush() {
            if (!passthrough && stageFrames > 0) processBlock()
            val rs = resampler
            if (rs != null) {
                outBuf.clear()
                val produced = rs.flush(outBuf, OUT_MAX)
                if (produced > 0) flushOut(produced)
            }
            flushOut()
        }
    }
}
