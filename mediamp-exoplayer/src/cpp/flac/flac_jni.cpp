/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/**
 * JNI bridge for the bundled FLAC software decoder (libmediamp_flac).
 *
 * Why this exists: Media3 has no FLAC software decoder and some devices only expose a hardware
 * FLAC decoder that fails on 24-bit streams (observed: OMX.qti.audio.decoder.flac on API 26,
 * "Decoder failed" out of native_queueInputBuffer). MediaCodec has no extension point that could
 * register a bundled decoder, so the only way to decode those streams is to own the decoding.
 *
 * Contract with org.openani.mediamp.exoplayer.internal.FlacDecoderNative.
 *
 * The Kotlin side feeds one FLAC frame at a time (what a container extractor hands out), so the
 * decoder cannot use dr_flac's stream API, which is pull based and needs random access over the
 * whole stream. Instead every frame is wrapped in a minimal self-contained FLAC stream —
 *
 *     "fLaC" | metadata block header (type STREAMINFO, 34 bytes) | 34-byte STREAMINFO | frame
 *
 * and decoded with drflac_open_memory. That keeps the JNI surface a pure push model, which is
 * what Media3's Decoder interface expects.
 *
 * DR_FLAC_IMPLEMENTATION is compiled by dr_flac_impl.cpp in this same directory, so this file
 * only sees the declarations.
 */

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

#include "thirdparty/dr_flac.h"

namespace {

constexpr size_t kStreamInfoSize = 34;
constexpr size_t kMetadataHeaderSize = 4;
constexpr size_t kFlacSignatureSize = 4;
constexpr size_t kWrappedHeaderSize = kFlacSignatureSize + kMetadataHeaderSize + kStreamInfoSize;

// Must match FlacDecoderNative.packStreamInfo in Kotlin.
constexpr jint kStreamInfoSampleRateShift = 44;
constexpr jint kStreamInfoChannelsShift = 41;

/**
 * Builds the bytes that precede one FLAC frame.
 *
 * `streamInfo` is the decoder configuration as Media3 reports it, and every extractor that can
 * produce an `audio/flac` track hands over the bare 34-byte STREAMINFO body:
 *  - MatroskaExtractor forwards the Matroska `A_FLAC` CodecPrivate unchanged, and
 *  - FlacExtractor consumes the "fLaC" signature and the metadata block header itself and keeps
 *    only the STREAMINFO body.
 *
 * So the prologue is always the signature plus one STREAMINFO metadata block header; there is no
 * whole-header shape to deal with. Anything that is not exactly the expected size is rejected,
 * which makes the renderer report an undecodable track instead of feeding dr_flac garbage.
 */
bool buildPrologue(
    const uint8_t *streamInfo,
    size_t streamInfoSize,
    std::vector<uint8_t> &out
) {
    if (streamInfo == nullptr || streamInfoSize != kStreamInfoSize) {
        return false;
    }

    out.resize(kWrappedHeaderSize);
    std::memcpy(out.data(), "fLaC", kFlacSignatureSize);
    // Metadata block header: last-block flag set, type 0 (STREAMINFO), 24-bit big-endian length.
    out[kFlacSignatureSize] = 0x80;
    out[kFlacSignatureSize + 1] = 0x00;
    out[kFlacSignatureSize + 2] = 0x00;
    out[kFlacSignatureSize + 3] = static_cast<uint8_t>(kStreamInfoSize);
    std::memcpy(out.data() + kFlacSignatureSize + kMetadataHeaderSize, streamInfo, kStreamInfoSize);
    return true;
}

/** Reads the STREAMINFO fields this file needs; kept in one place so the shifts stay adjacent. */
struct StreamInfoFields {
    uint32_t sampleRate;
    uint32_t channels;
    uint32_t bitsPerSample;
    uint32_t maxBlockSize;
};

StreamInfoFields readStreamInfo(const uint8_t *streamInfo) {
    StreamInfoFields fields{};
    const uint8_t *body = streamInfo;
    fields.maxBlockSize = (static_cast<uint32_t>(body[0]) << 8) | body[1];
    const uint64_t packed =
        (static_cast<uint64_t>(body[10]) << 56) | (static_cast<uint64_t>(body[11]) << 48) |
        (static_cast<uint64_t>(body[12]) << 40) | (static_cast<uint64_t>(body[13]) << 32) |
        (static_cast<uint64_t>(body[14]) << 24) | (static_cast<uint64_t>(body[15]) << 16) |
        (static_cast<uint64_t>(body[16]) << 8) | static_cast<uint64_t>(body[17]);
    fields.sampleRate = static_cast<uint32_t>((packed >> kStreamInfoSampleRateShift) & 0xFFFFF);
    fields.channels = static_cast<uint32_t>(((packed >> kStreamInfoChannelsShift) & 0x7) + 1);
    fields.bitsPerSample = static_cast<uint32_t>(((packed >> 36) & 0x1F) + 1);
    return fields;
}

std::vector<uint8_t> copyBuffer(JNIEnv *env, jobject buffer, jint size) {
    std::vector<uint8_t> result;
    if (buffer == nullptr || size <= 0) {
        return result;
    }
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (base != nullptr) {
        result.assign(base, base + size);
        return result;
    }
    // A heap ByteBuffer cannot be addressed directly; copy through the array API.
    auto *array = static_cast<jbyteArray>(env->NewByteArray(size));
    if (array == nullptr) {
        return result;
    }
    env->GetByteArrayRegion(reinterpret_cast<jbyteArray>(buffer), 0, size, array);
    result.resize(static_cast<size_t>(size));
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(array);
    return result;
}

}  // namespace

extern "C" {

/**
 * Decodes one FLAC frame.
 *
 * @param streamInfo  decoder configuration; a bare 34-byte STREAMINFO or a full FLAC header.
 * @param frame       one complete FLAC frame, as handed out by the container extractor.
 * @param output      direct buffer, at least `outputCapacity` bytes.
 * @return number of PCM frames written, or -1 when the frame could not be decoded.
 */
JNIEXPORT jint JNICALL
Java_org_openani_mediamp_exoplayer_internal_FlacDecoderNative_nativeDecodeFrame(
    JNIEnv *env,
    jclass /* clazz */,
    jobject streamInfoBuffer,
    jint streamInfoSize,
    jobject frameBuffer,
    jint frameSize,
    jobject outputBuffer,
    jint outputCapacity
) {
    if (outputBuffer == nullptr || outputCapacity <= 0) {
        return -1;
    }
    auto *output = static_cast<uint8_t *>(env->GetDirectBufferAddress(outputBuffer));
    if (output == nullptr) {
        return -1;
    }

    std::vector<uint8_t> streamInfo = copyBuffer(env, streamInfoBuffer, streamInfoSize);
    std::vector<uint8_t> frame = copyBuffer(env, frameBuffer, frameSize);
    if (frame.empty()) {
        return -1;
    }

    std::vector<uint8_t> wrapped;
    if (!buildPrologue(streamInfo.data(), streamInfo.size(), wrapped)) {
        return -1;
    }

    // The prologue is always signature + metadata block header + the 34-byte STREAMINFO body,
    // so the fields start at a fixed offset. Reading them from the wrapped copy keeps a single
    // source of truth for the layout.
    const StreamInfoFields fields =
        readStreamInfo(wrapped.data() + kFlacSignatureSize + kMetadataHeaderSize);
    if (fields.channels == 0 || fields.channels > 8 || fields.maxBlockSize == 0) {
        return -1;
    }

    wrapped.insert(wrapped.end(), frame.begin(), frame.end());

    drflac *decoder = drflac_open_memory(wrapped.data(), wrapped.size(), nullptr);
    if (decoder == nullptr) {
        return -1;
    }

    const uint64_t maxFrames = fields.maxBlockSize;
    const auto availableFrames = static_cast<uint64_t>(
        outputCapacity / (static_cast<int>(fields.channels) * static_cast<int>(sizeof(int32_t))));
    const uint64_t framesToRead = maxFrames < availableFrames ? maxFrames : availableFrames;
    if (framesToRead == 0) {
        drflac_close(decoder);
        return -1;
    }

    const drflac_uint64 decoded = drflac_read_pcm_frames_s32(
        decoder,
        framesToRead,
        reinterpret_cast<drflac_int32 *>(output)
    );
    drflac_close(decoder);
    if (decoded == 0 || decoded > static_cast<drflac_uint64>(INT32_MAX)) {
        return -1;
    }
    return static_cast<jint>(decoded);
}

}  // extern "C"
