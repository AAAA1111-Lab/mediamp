/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the STREAMINFO packing the bundled FLAC decoder depends on.
 *
 * The packed bytes are handed to dr_flac inside a synthetic FLAC stream, so a wrong bit shows up
 * as silence or a decode failure on a device rather than as an error at build time. That is exactly
 * what happened once: the field bytes were written starting at bit 40 instead of 48, which dropped
 * the high bits of the sample rate and shifted the other fields by four bits. These tests read the
 * fields back the way RFC 9639 §8.2 defines them, so the layout is pinned independently of the
 * code that writes it.
 */
class FlacStreamInfoTest {
    @Test
    fun `writes the block sizes into the first four bytes`() {
        val streamInfo = pack(maxBlockSize = 4096)

        assertEquals(4096, readU16(streamInfo, 0))
        assertEquals(4096, readU16(streamInfo, 2))
        // Minimum and maximum frame size stay unknown.
        assertEquals(0, readU24(streamInfo, 4))
        assertEquals(0, readU24(streamInfo, 7))
    }

    @Test
    fun `places sample rate channels and bit depth at the spec offsets`() {
        val streamInfo = pack(sampleRate = 96000, channelCount = 2, bitsPerSample = 24)

        // Sample rate occupies bits 63..44 of the 64-bit field at [10..17].
        assertEquals(96000L, streamInfo.readFieldBits(bitOffset = 44, width = 20))
        // Channels - 1 occupies bits 43..41.
        assertEquals(1L, streamInfo.readFieldBits(bitOffset = 41, width = 3))
        // Bits per sample - 1 occupies bits 40..36.
        assertEquals(23L, streamInfo.readFieldBits(bitOffset = 36, width = 5))
        // Total samples occupies bits 35..0 and stays unknown.
        assertEquals(0L, streamInfo.readFieldBits(bitOffset = 0, width = 36))
    }

    @Test
    fun `handles the extremes of every field`() {
        val streamInfo = pack(
            sampleRate = 655350,
            channelCount = 8,
            bitsPerSample = 32,
            maxBlockSize = 65535,
        )

        assertEquals(65535, readU16(streamInfo, 0))
        assertEquals(655350L, streamInfo.readFieldBits(bitOffset = 44, width = 20))
        assertEquals(7L, streamInfo.readFieldBits(bitOffset = 41, width = 3))
        assertEquals(31L, streamInfo.readFieldBits(bitOffset = 36, width = 5))
    }

    @Test
    fun `keeps a 44100 stereo 16 bit stream intact`() {
        val streamInfo = pack(sampleRate = 44100, channelCount = 2, bitsPerSample = 16, maxBlockSize = 4608)

        assertEquals(44100L, streamInfo.readFieldBits(bitOffset = 44, width = 20))
        assertEquals(1L, streamInfo.readFieldBits(bitOffset = 41, width = 3))
        assertEquals(15L, streamInfo.readFieldBits(bitOffset = 36, width = 5))
    }

    @Test
    fun `produces exactly the 34 byte block`() {
        assertEquals(FlacStreamInfo.SIZE, pack().size)
    }

    @Test
    fun `accepts a bare 34 byte stream info`() {
        val body = pack()

        assertContentEquals(body, FlacStreamInfo.extractStreamInfo(body))
    }

    @Test
    fun `accepts a stream info preceded by its metadata block header`() {
        // What a real 24-bit Matroska release reported: 42 bytes of codec private. That is a
        // metadata block header (last-block flag, type 0, length 34), the body, and a further four
        // bytes the container carried; the body must be found where the header points.
        val body = pack(sampleRate = 48000, channelCount = 2, bitsPerSample = 24)
        val reported = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + body + byteArrayOf(0, 0, 0, 0)

        assertEquals(42, reported.size)
        assertContentEquals(body, FlacStreamInfo.extractStreamInfo(reported))
    }

    @Test
    fun `accepts a full flac header with extra metadata after stream info`() {
        // 113 bytes were reported for another stream: a seek table follows the stream info, so the
        // chain has to be walked rather than a fixed length assumed.
        val body = pack(sampleRate = 48000, channelCount = 2, bitsPerSample = 24)
        val seekTableLength = 113 - 4 - FlacStreamInfo.SIZE - 4
        val header = byteArrayOf(0x00, 0x00, 0x00, FlacStreamInfo.SIZE.toByte()) + body +
            byteArrayOf(0x83.toByte(), 0x00, 0x00, seekTableLength.toByte()) +
            ByteArray(seekTableLength)

        assertEquals(113, header.size)
        assertContentEquals(body, FlacStreamInfo.extractStreamInfo(header))
    }

    @Test
    fun `accepts a full flac file header with the signature`() {
        val body = pack(sampleRate = 48000, channelCount = 2, bitsPerSample = 16)
        val file = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + body

        assertContentEquals(body, FlacStreamInfo.extractStreamInfo(file))
    }

    @Test
    fun `rejects a configuration with no stream info`() {
        assertNull(FlacStreamInfo.extractStreamInfo(byteArrayOf(0x01, 0x02, 0x03)))
        // A block chain whose only block is not STREAMINFO.
        assertNull(FlacStreamInfo.extractStreamInfo(byteArrayOf(0x84.toByte(), 0x00, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte())))
    }

    private fun pack(
        sampleRate: Int = 48000,
        channelCount: Int = 2,
        bitsPerSample: Int = 24,
        maxBlockSize: Int = 4096,
    ) = FlacStreamInfo.pack(sampleRate, channelCount, bitsPerSample, maxBlockSize)

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readU24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    /**
     * Reads [width] bits starting at [bitOffset] from the least significant bit of the 64-bit field
     * at bytes [10..17]; bit 63 is the most significant bit of byte 10.
     */
    private fun ByteArray.readFieldBits(bitOffset: Int, width: Int): Long {
        var value = 0L
        for (index in 10..17) {
            value = (value shl 8) or (this[index].toLong() and 0xFF)
        }
        return (value ushr bitOffset) and ((1L shl width) - 1)
    }
}
