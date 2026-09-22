/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import java.nio.ByteBuffer

/**
 * Raw JNI surface of the bundled FLAC software decoder (`libmediamp_flac.so`).
 *
 * The native side is a pure push model: [nativeDecodeFrame] takes one FLAC frame exactly as a
 * container extractor hands it out, wraps it in a minimal self-contained FLAC stream together
 * with the codec configuration, decodes it with the bundled dr_flac, and writes interleaved
 * 32-bit PCM to [outputBuffer]. See `src/cpp/flac_jni.cpp` for the C++ contract.
 *
 * Every method is static and takes no locks: a decoder instance owns its buffers and is only
 * driven from the playback thread.
 */
internal object FlacDecoderNative {
    init {
        System.loadLibrary("mediamp_flac")
    }

    /**
     * Decodes one FLAC frame.
     *
     * @param streamInfoBuffer the decoder configuration Media3 reports for the track: the bare
     *   34-byte STREAMINFO body, which is what every extractor able to produce an `audio/flac`
     *   track hands over (Matroska's `A_FLAC` CodecPrivate, and FlacExtractor after it consumes
     *   the signature and block header itself).
     * @param frameBuffer one complete FLAC frame.
     * @param outputBuffer direct buffer to receive interleaved 32-bit PCM.
     * @return the number of PCM frames written, or a negative value when the frame is undecodable.
     */
    @JvmStatic
    external fun nativeDecodeFrame(
        streamInfoBuffer: ByteBuffer,
        streamInfoSize: Int,
        frameBuffer: ByteBuffer,
        frameSize: Int,
        outputBuffer: ByteBuffer,
        outputCapacity: Int,
    ): Int

    /**
     * Packs the six STREAMINFO fields a FLAC decoder needs into the layout of the STREAMINFO
     * block body (34 bytes; only the first 18 are written, the rest stays zeroed).
     *
     * Keeping this in Kotlin means the native side only has to combine the configuration with the
     * frame, and the shifts stay next to the comment that explains them.
     *
     * STREAMINFO layout (RFC 9639 §8.2):
     *   [0..1]   minimum block size
     *   [2..3]   maximum block size
     *   [4..6]   minimum frame size (0 = unknown)
     *   [7..9]   maximum frame size (0 = unknown)
     *   [10..12] sample rate (20 bits) | channels - 1 (3 bits) | bits per sample - 1 (5 bits)
     *   [13..17] total samples (36 bits), 0 = unknown
     */
    @JvmStatic
    fun packStreamInfo(
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        maxBlockSize: Int,
    ): ByteArray {
        val streamInfo = ByteArray(34)
        streamInfo[0] = ((maxBlockSize ushr 8) and 0xFF).toByte()
        streamInfo[1] = (maxBlockSize and 0xFF).toByte()
        streamInfo[2] = ((maxBlockSize ushr 8) and 0xFF).toByte()
        streamInfo[3] = (maxBlockSize and 0xFF).toByte()
        // Frame sizes are left at 0 (unknown): the extractor does not report them and dr_flac
        // does not require them.

        // 20 bits of sample rate, 3 bits of (channels - 1), 5 bits of (bits per sample - 1).
        val packed = ((sampleRate.toLong() and 0xFFFFF) shl 44) or
            (((channelCount - 1).toLong() and 0x7) shl 41) or
            (((bitsPerSample - 1).toLong() and 0x1F) shl 36)
        for (index in 0 until 6) {
            streamInfo[10 + index] = ((packed ushr (40 - index * 8)) and 0xFF).toByte()
        }
        // Total samples stays 0 (unknown).
        return streamInfo
    }
}
