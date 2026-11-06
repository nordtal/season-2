package eu.nordtal.season.build

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ogg Vorbis, the one audio format Minecraft plays. It tells a Vorbis file from any other and decodes one to a WAV that
 * every operating system's own player plays, as the client would play it.
 */
object OggVorbis {
    /** Whether [bytes] start as an Ogg stream whose first packet is a Vorbis identification header. */
    fun isVorbis(bytes: ByteArray): Boolean {
        if (bytes.size < 28 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "OggS") return false
        val packet = 27 + (bytes[26].toInt() and 0xff)
        return bytes.size >= packet + 7 && bytes[packet].toInt() == 1 && String(bytes, packet + 1, 6, Charsets.ISO_8859_1) == "vorbis"
    }

    /** Decoded audio: [samples] interleaved over [channels], at [rate] per second. */
    class Pcm(
        val channels: Int,
        val rate: Int,
        val samples: FloatArray,
    )

    /**
     * Decodes the first logical stream of the Ogg Vorbis file [bytes].
     *
     * @throws IllegalArgumentException if [bytes] is no Ogg Vorbis file
     */
    fun decode(bytes: ByteArray): Pcm {
        require(isVorbis(bytes)) { "The file is no Ogg Vorbis file." }
        val sync = SyncState().apply { init() }
        val at = sync.buffer(bytes.size)
        System.arraycopy(bytes, 0, sync.data, at, bytes.size)
        sync.wrote(bytes.size)
        val page = Page()
        val packet = Packet()
        val info = Info().apply { init() }
        val comment = Comment().apply { init() }
        require(sync.pageout(page) == 1) { "The file holds no Ogg page." }
        val serial = page.serialno()
        val stream = StreamState().apply { init(serial) }
        stream.pagein(page)
        var headers = 0
        val dsp = DspState()
        val block = Block(dsp)
        val out = FloatArrayBuilder()
        val pcm = arrayOfNulls<Array<FloatArray>>(1)
        var index = IntArray(0)
        while (true) {
            while (true) {
                val result = stream.packetout(packet)
                if (result == 0) break
                if (result < 0) continue
                if (headers < 3) {
                    require(info.synthesis_headerin(comment, packet) >= 0) { "The file has a broken Vorbis header." }
                    if (++headers == 3) {
                        dsp.synthesis_init(info)
                        block.init(dsp)
                        index = IntArray(info.channels)
                    }
                    continue
                }
                if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block)
                while (true) {
                    @Suppress("UNCHECKED_CAST")
                    val count = dsp.synthesis_pcmout(pcm as Array<Array<FloatArray>>, index)
                    if (count <= 0) break
                    val channels = pcm[0]!!
                    for (i in 0 until count) for (c in 0 until info.channels) out += channels[c][index[c] + i]
                    dsp.synthesis_read(count)
                }
            }
            if (page.eos() != 0 || sync.pageout(page) != 1) break
            if (page.serialno() == serial) stream.pagein(page)
        }
        require(headers == 3) { "The file ends before its Vorbis headers." }
        return Pcm(info.channels, info.rate, out.toArray())
    }

    /**
     * [pcm] as a 16-bit WAV, played as the client plays a sound at [volume] and [pitch]: the client caps the volume at 1
     * and holds the pitch between 0.5 and 2, and a pitch plays the sound faster or slower, so it scales the sample rate.
     */
    fun wav(
        pcm: Pcm,
        volume: Double,
        pitch: Double,
    ): ByteArray {
        val gain = volume.coerceIn(0.0, 1.0)
        val rate = (pcm.rate * pitch.coerceIn(0.5, 2.0)).toInt()
        val data = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.samples.forEach {
            data.putShort(
                (it * gain)
                    .coerceIn(-1.0, 1.0)
                    .times(Short.MAX_VALUE)
                    .toInt()
                    .toShort(),
            )
        }
        val header =
            ByteBuffer
                .allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(36 + data.capacity())
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(pcm.channels.toShort())
                .putInt(rate)
                .putInt(rate * pcm.channels * 2)
                .putShort((pcm.channels * 2).toShort())
                .putShort(16)
                .put("data".toByteArray())
                .putInt(data.capacity())
        return ByteArrayOutputStream()
            .also {
                it.write(header.array())
                it.write(data.array())
            }.toByteArray()
    }

    private class FloatArrayBuilder {
        private var values = FloatArray(1 shl 16)
        private var size = 0

        operator fun plusAssign(value: Float) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray() = values.copyOf(size)
    }
}
