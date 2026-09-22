/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [SimpleDecoder] backed by the bundled FLAC software decoder.
 *
 * Media3 ships no FLAC software decoder, and [androidx.media3.exoplayer.mediacodec.MediaCodecSelector]
 * cannot help: it only orders decoders the platform already reports, so on a device whose only
 * FLAC decoder is a hardware one that chokes on 24-bit streams there is nothing to select. Owning
 * the decode is the only way in.
 *
 * The decoder is a thin adapter: every input buffer holds exactly one FLAC frame as produced by
 * the container extractor, which [FlacDecoderNative.nativeDecodeFrame] wraps into a self-contained
 * stream and decodes. There is no internal sample queue and no cross-frame state, which keeps the
 * output aligned with the input timestamps that Media3 hands over — including across seeks, since
 * Media3 flushes the decoder and the extractor then continues from the new position.
 *
 * Output is interleaved 32-bit PCM, which covers 16/20/24-bit sources losslessly.
 */
@OptIn(UnstableApi::class)
internal class FlacDecoder private constructor(
    private val inputBufferSize: Int,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FlacDecoderException>(
    Array(OUTPUT_BUFFER_COUNT) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL) },
    Array(OUTPUT_BUFFER_COUNT) { FlacDecoderOutputBuffer() },
) {
    private var streamInfo: ByteArray? = null
    private var streamInfoBuffer: ByteBuffer? = null
    private var outputFormat: Format? = null
    private var channels: Int = 0
    private var sampleRate: Int = 0
    private var outputBufferCapacity: Int = 0

    override fun getName(): String = "mediamp-flac"

    /**
     * Records the codec configuration and the output shape.
     *
     * [configurationData] is what Media3 reports for the track: a bare 34-byte STREAMINFO block
     * for Matroska extraction, or a full FLAC header. [FlacDecoderNative] accepts both, so the
     * bytes are forwarded unchanged; only the format fields are read here.
     */
    fun configure(format: Format, configurationData: ByteArray?) {
        val data = configurationData
            ?: throw FlacDecoderException("FLAC track has no codec configuration (csd-0)")
        val channelCount = format.channelCount
        val sampleRateHz = format.sampleRate
        if (channelCount <= 0 || channelCount > MAX_CHANNELS) {
            throw FlacDecoderException("Unsupported FLAC channel count: $channelCount")
        }
        if (sampleRateHz <= 0) {
            throw FlacDecoderException("Unsupported FLAC sample rate: $sampleRateHz")
        }

        streamInfo = data
        streamInfoBuffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {
            put(data)
            flip()
        }
        channels = channelCount
        this.sampleRate = sampleRateHz
        outputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRateHz)
            .build()
        // dr_flac decodes at most maxBlockSize frames per call, and a frame never exceeds the
        // FLAC maximum block size, so this bound holds for every frame.
        outputBufferCapacity = MAX_BLOCK_SIZE * channelCount * BYTES_PER_SAMPLE
    }

    /** The PCM format produced by this decoder. Set by [configure] before decoding starts. */
    fun requireOutputFormat(): Format =
        outputFormat ?: throw FlacDecoderException("FLAC decoder used before configure()")

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        FlacDecoderOutputBuffer()

    override fun createUnexpectedDecodeException(error: Throwable): FlacDecoderException =
        FlacDecoderException("Unexpected FLAC decode failure", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): FlacDecoderException? {
        if (reset) {
            // The decoder holds no state across frames, so a reset is a no-op.
            return null
        }
        val input = inputBuffer.data ?: return null
        val frameSize = input.remaining()
        if (frameSize <= 0) {
            return null
        }
        val info = streamInfo
        val infoBuffer = streamInfoBuffer
        if (info == null || infoBuffer == null) {
            return FlacDecoderException("FLAC decoder used before configure()")
        }

        val capacity = outputBufferCapacity
        val output = outputBuffer.init(inputBuffer.timeUs, capacity).order(ByteOrder.nativeOrder())
        val decodedFrames = try {
            FlacDecoderNative.nativeDecodeFrame(
                streamInfoBuffer = infoBuffer,
                streamInfoSize = info.size,
                frameBuffer = input,
                frameSize = frameSize,
                outputBuffer = output,
                outputCapacity = capacity,
            )
        } catch (error: Throwable) {
            return FlacDecoderException("FLAC software decode failed", error)
        }
        if (decodedFrames <= 0) {
            return FlacDecoderException("FLAC software decode produced no samples")
        }

        output.position(0)
        output.limit(decodedFrames * channels * BYTES_PER_SAMPLE)
        return null
    }

    override fun release() {
        streamInfo = null
        streamInfoBuffer = null
        outputFormat = null
        super.release()
    }

    companion object {
        /** FLAC's maximum block size (RFC 9639 §9.1.1). */
        private const val MAX_BLOCK_SIZE = 65535
        private const val BYTES_PER_SAMPLE = 4
        private const val MAX_CHANNELS = 8
        private const val OUTPUT_BUFFER_COUNT = 4
        private const val DEFAULT_INPUT_BUFFER_SIZE = 64 * 1024

        /**
         * Whether the bundled decoder is actually loadable. Callers must check this before
         * preferring software decoding: the native library exists for every shipped ABI, but a
         * split APK or an unsupported ABI would otherwise turn every FLAC track into a
         * PlaybackException instead of falling back to the platform decoder.
         */
        val isAvailable: Boolean by lazy {
            runCatching {
                // Touching the object runs its static initializer, which loads the library.
                FlacDecoderNative
            }.isSuccess
        }

        /**
         * Creates a decoder for [format], or null when the track is not something this decoder
         * can handle — in which case the caller falls back to Media3's hardware path.
         */
        fun createOrNull(format: Format, cryptoConfig: CryptoConfig?): FlacDecoder? {
            if (!isAvailable) return null
            if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) return null
            // FLAC is never DRM protected; anything encrypted belongs on the platform path.
            if (cryptoConfig != null) return null
            val configuration = format.initializationData.firstOrNull() ?: return null
            return try {
                FlacDecoder(inputBufferSize = DEFAULT_INPUT_BUFFER_SIZE).apply {
                    configure(format, configuration)
                }
            } catch (error: FlacDecoderException) {
                null
            }
        }
    }
}

/** Releases the buffer back to its decoder, which is how Media3 recycles decoded PCM. */
private class FlacDecoderOutputBuffer : SimpleDecoderOutputBuffer(Owner { it.release() })

private fun interface Owner {
    fun release(buffer: SimpleDecoderOutputBuffer)
}

internal class FlacDecoderException : androidx.media3.decoder.DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
