package dev.jay.usbdirect

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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

    @Volatile private var running = false
    private var thread: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var engine: UsbAudioEngine? = null

    val isRunning: Boolean get() = running

    fun start(device: UsbDevice, uri: Uri, rate: Int, bits: Int, volume: Float,
              startMs: Long = 0L) {
        // Play at the file's own rate when the DAC supports it, so nothing is
        // resampled; otherwise fall back to the requested rate.
        val src = probeRate(uri)
        val dev = if (src in SUPPORTED_RATES) src else rate
        Log.i("VCProbe", "source=" + src + " Hz -> device=" + dev + " Hz" +
                if (src == dev) " (bit-perfect path, no resample)" else " (resampled)")
        startInternal(device, dev, bits, volume) { eng -> decodeLoop(uri, eng, dev, startMs) }
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

    /** Plays a 1 kHz tone straight from native, no decoding involved. */
    fun startTone(device: UsbDevice, rate: Int, bits: Int, volume: Float, seconds: Int) =
        startInternal(device, rate, bits, volume) { eng -> playTone(eng, rate, seconds) }

    private fun startInternal(device: UsbDevice, rate: Int, bits: Int, volume: Float,
                              body: (UsbAudioEngine) -> Unit) {
        stop()
        running = true
        thread = Thread {
            var conn: UsbDeviceConnection? = null
            var eng: UsbAudioEngine? = null
            try {
                val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
                conn = mgr.openDevice(device)
                    ?: throw IllegalStateException("openDevice failed - USB permission?")
                connection = conn

                eng = UsbAudioEngine.open(conn.fileDescriptor, rate, bits)
                if (!eng.isOk) {
                    val msg = eng.error
                    eng.close()
                    throw IllegalStateException(msg)
                }
                engine = eng
                eng.setVolume(volume)

                Log.i("VCProbe", "engine open ok: " + rate + " Hz / " + bits + "-bit")
                onInfo?.invoke("engine: ${rate} Hz / $bits-bit")
                body(eng)
            } catch (t: Throwable) {
                Log.e("VCProbe", "engine failed: ${t.message}", t)
                if (running) onError?.invoke(t.message ?: t.toString())
            } finally {
                eng?.close()
                engine = null
                conn?.close()
                connection = null
                running = false
                onFinished?.invoke()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        engine?.close()
        engine = null
        connection?.close()
        connection = null
        thread?.interrupt()
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        thread = null
    }

    fun setVolume(v: Float) { engine?.setVolume(v) }
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

    private fun decodeLoop(uri: Uri, eng: UsbAudioEngine, outRate: Int, startMs: Long) {
        val extractor = MediaExtractor()
        if (uri.scheme == "file") {
            extractor.setDataSource(uri.path!!)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }
        }

        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; format = f; break }
        }
        if (track < 0 || format == null) throw IllegalStateException("no audio track")
        extractor.selectTrack(track)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        onInfo?.invoke("$mime  $srcRate Hz  ${srcCh}ch  ->  $outRate Hz")

        Log.i("VCProbe", "track mime=" + mime + " rate=" + srcRate + " ch=" + srcCh)
        if (startMs > 0) {
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            Log.i("VCProbe", "seek to " + startMs + " ms (sampleTime=" + extractor.sampleTime + ")")
        }
        val sink = PcmSink(eng, srcRate, outRate, srcCh, startMs, onProgress)

        if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
            decodeRaw(extractor, sink, format)
        } else {
            decodeCodec(extractor, format, track, sink)
        }
        sink.flush()
        extractor.release()
        Log.i("VCProbe", "decode loop finished")
    }

    private fun decodeRaw(extractor: MediaExtractor, sink: PcmSink, format: MediaFormat) {
        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2 /* PCM_16BIT */

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
    private class PcmSink(
        private val eng: UsbAudioEngine,
        private val srcRate: Int,
        private val outRate: Int,
        private val srcCh: Int,
        private val startMs: Long,
        private val progress: ((Long) -> Unit)?
    ) {
        private var lastReportMs = 0L
        private val ratio = srcRate.toDouble() / outRate.toDouble()
        private val chunk = 4096
        private val direct: FloatBuffer =
            ByteBuffer.allocateDirect(chunk * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()

        private var t = 0.0
        private var prevL = 0f
        private var prevR = 0f
        private var havePrev = false

        fun pushShorts(buf: java.nio.ShortBuffer) {
            val frames = buf.remaining() / srcCh
            for (i in 0 until frames) {
                val l = buf.get().toFloat() / 32768f
                val r = if (srcCh >= 2) buf.get().toFloat() / 32768f else l
                for (k in 2 until srcCh) buf.get()
                frame(l, r)
            }
            flushChunk()
        }

        fun push24(buf: ByteBuffer) {
            val frames = buf.remaining() / (3 * srcCh)
            for (i in 0 until frames) {
                val l = read24(buf)
                val r = if (srcCh >= 2) read24(buf) else l
                for (k in 2 until srcCh) buf.position(buf.position() + 3)
                frame(l, r)
            }
            flushChunk()
        }

        private fun read24(b: ByteBuffer): Float {
            val b0 = b.get().toInt() and 0xFF
            val b1 = b.get().toInt() and 0xFF
            val b2 = b.get().toInt()
            var v = (b2 shl 16) or (b1 shl 8) or b0
            if (v and 0x800000 != 0) v = v or -0x1000000
            return v / 8388608f
        }

        fun pushFloat(buf: FloatBuffer) {
            val frames = buf.remaining() / srcCh
            for (i in 0 until frames) {
                val l = buf.get()
                val r = if (srcCh >= 2) buf.get() else l
                for (k in 2 until srcCh) buf.get()
                frame(l, r)
            }
            flushChunk()
        }

        private fun frame(l: Float, r: Float) {
            if (!havePrev) { prevL = l; prevR = r; havePrev = true; return }
            while (t < 1.0) {
                val a = t.toFloat()
                emit(prevL + (l - prevL) * a, prevR + (r - prevR) * a)
                t += ratio
            }
            t -= 1.0
            prevL = l; prevR = r
        }

        private fun emit(l: Float, r: Float) {
            if (direct.remaining() < 2) flushChunk()
            direct.put(l); direct.put(r)
        }

        private fun flushChunk() {
            val frames = direct.position() / 2
            if (frames == 0) return
            direct.flip()
            eng.write(direct, frames)
            direct.clear()
        }

        fun flush() = flushChunk()
    }
}
