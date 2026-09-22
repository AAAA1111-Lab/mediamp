/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

/**
 * Packs the STREAMINFO body the FLAC decoder is configured with.
 *
 * Kept apart from [FlacDecoderNative] on purpose: that object loads the native library when it is
 * first touched, so anything reachable through it cannot be exercised on a host JVM.
 *
 * STREAMINFO layout (RFC 9639 §8.2), 34 bytes total:
 *   [0..1]   minimum block size
 *   [2..3]   maximum block size
 *   [4..6]   minimum frame size (0 = unknown)
 *   [7..9]   maximum frame size (0 = unknown)
 *   [10..12] sample rate (20 bits) | channels - 1 (3 bits) | bits per sample - 1 (5 bits)
 *   [13..17] total samples (36 bits), 0 = unknown
 *   [18..33] MD5 of the unencoded audio (0 = unknown)
 */
internal object FlacStreamInfo {
    const val SIZE = 34

    fun pack(
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        maxBlockSize: Int,
    ): ByteArray {
        val streamInfo = ByteArray(SIZE)
        // Minimum and maximum block size: the extractor reports one value for both.
        streamInfo[0] = ((maxBlockSize ushr 8) and 0xFF).toByte()
        streamInfo[1] = (maxBlockSize and 0xFF).toByte()
        streamInfo[2] = ((maxBlockSize ushr 8) and 0xFF).toByte()
        streamInfo[3] = (maxBlockSize and 0xFF).toByte()
        // Frame sizes stay 0 (unknown): the extractor does not report them and dr_flac does not
        // require them.

        // 20 bits of sample rate, 3 bits of (channels - 1), 5 bits of (bits per sample - 1).
        // Together with the 36 bits of total samples below, these 64 bits map onto the eight
        // bytes [10..17], most significant byte first.
        val packed = ((sampleRate.toLong() and 0xFFFFF) shl 44) or
            (((channelCount - 1).toLong() and 0x7) shl 41) or
            (((bitsPerSample - 1).toLong() and 0x1F) shl 36)
        for (index in 0 until 8) {
            // Byte `index` of [10..17] holds bits (63 - index * 8) down to (56 - index * 8).
            streamInfo[10 + index] = ((packed ushr (56 - index * 8)) and 0xFF).toByte()
        }
        // Total samples and MD5 stay 0 (unknown).
        return streamInfo
    }
}
