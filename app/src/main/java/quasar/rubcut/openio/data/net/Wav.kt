package quasar.rubcut.openio.data.net

import java.io.ByteArrayOutputStream

/** WAV wrapper for the mono 16 kHz microphone stream. */
object Wav {
    fun encodePcm16(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)

        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(16)
        le16(1) // PCM
        le16(channels)
        le32(sampleRate)
        le32(sampleRate * channels * 2)
        le16(channels * 2)
        le16(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(dataBytes)
        for (sample in samples) {
            le16(sample.toInt())
        }
        return out.toByteArray()
    }
}