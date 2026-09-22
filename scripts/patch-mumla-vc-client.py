#!/usr/bin/env python3
"""Patch pinned Mumla/Humla source for VC per-listener gain support."""

from __future__ import annotations

import argparse
import pathlib
import sys


MUMALA_PIN = "477b337ebcee1655c1357d51db99cf92bbc175a4"
HUMLA_PIN = "7966f3828d6ed87ef29c517abfade6ad7998cdc5"
GAIN_MAGIC = "VCG1"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one {label}; found {count}")
    return text.replace(old, new, 1)


def patch_audio_output(path: pathlib.Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "VC_GAIN_TRAILER" in text:
        return

    text = replace_once(
        text,
        "    private ExecutorService mDecodeExecutorService;\n",
        "    private ExecutorService mDecodeExecutorService;\n"
        "    private long mVcLastGainLogMs = 0L; // VC_GAIN_DIAGNOSTIC\n",
        "AudioOutput diagnostic field",
    )

    old = """            PacketBuffer dataBuffer = new PacketBuffer(pds.bufferBlock(pds.left()));
            aop.addFrameToBuffer(dataBuffer, msgFlags, seq);
"""
    new = """            byte[] vcVoicePayload = pds.dataBlock(pds.left());
            float vcServerGain = 1.0f; // VC_GAIN_TRAILER
            boolean vcHasGainTrailer = false;
            if (vcVoicePayload.length >= 8) {
                int vcOffset = vcVoicePayload.length - 8;
                if (vcVoicePayload[vcOffset] == 'V'
                        && vcVoicePayload[vcOffset + 1] == 'C'
                        && vcVoicePayload[vcOffset + 2] == 'G'
                        && vcVoicePayload[vcOffset + 3] == '1') {
                    int vcBits = (vcVoicePayload[vcOffset + 4] & 0xff)
                            | ((vcVoicePayload[vcOffset + 5] & 0xff) << 8)
                            | ((vcVoicePayload[vcOffset + 6] & 0xff) << 16)
                            | ((vcVoicePayload[vcOffset + 7] & 0xff) << 24);
                    float vcCandidate = Float.intBitsToFloat(vcBits);
                    if (Float.isFinite(vcCandidate) && vcCandidate >= 0.0f && vcCandidate <= 1.0f) {
                        vcServerGain = vcCandidate;
                        vcHasGainTrailer = true;
                        vcVoicePayload = Arrays.copyOf(vcVoicePayload, vcOffset);
                    }
                }
            }

            long vcNow = System.currentTimeMillis();
            if (vcNow - mVcLastGainLogMs >= 2000L) {
                mVcLastGainLogMs = vcNow;
                Log.i(TAG, "VC-GAIN rx session=" + session
                        + " gain=" + vcServerGain
                        + " trailer=" + vcHasGainTrailer
                        + " payloadBytes=" + vcVoicePayload.length);
            }

            PacketBuffer dataBuffer = new PacketBuffer(vcVoicePayload, vcVoicePayload.length);
            aop.addFrameToBuffer(dataBuffer, msgFlags, seq, vcServerGain);
"""
    text = replace_once(text, old, new, "AudioOutput queue handoff")
    path.write_text(text, encoding="utf-8")


def patch_audio_output_speech(path: pathlib.Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "VC_SERVER_GAIN_PCM" in text:
        return

    text = replace_once(
        text,
        "    private int mMissCount = 0;\n",
        "    private int mMissCount = 0;\n"
        "    private float mServerVolumeFactor = 1.0f; // VC_SERVER_GAIN_PCM\n",
        "server volume field",
    )

    text = replace_once(
        text,
        "    public void addFrameToBuffer(PacketBuffer pb, byte flags, int seq) {\n",
        "    public void addFrameToBuffer(PacketBuffer pb, byte flags, int seq, float serverVolumeFactor) {\n",
        "addFrameToBuffer signature",
    )

    old_packet = """                Speex.JitterBufferPacket packet = new Speex.JitterBufferPacket(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags);
"""
    new_packet = """                int vcGainByte = Math.max(0, Math.min(255, Math.round(serverVolumeFactor * 255.0f)));
                int vcUserData = (vcGainByte << 8) | (flags & 0xff);
                Speex.JitterBufferPacket packet = new Speex.JitterBufferPacket(
                        data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, vcUserData);
"""
    text = replace_once(text, old_packet, new_packet, "jitter packet gain metadata")

    old_flags = """                        ucFlags = jbp.getUserData();
"""
    new_flags = """                        int vcUserData = jbp.getUserData();
                        ucFlags = vcUserData & 0xff;
                        mServerVolumeFactor = ((vcUserData >>> 8) & 0xff) / 255.0f;
"""
    text = replace_once(text, old_flags, new_flags, "jitter gain restore")

    old_copy = """            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);
"""
    new_copy = """            if (mServerVolumeFactor < 0.999f) {
                for (int i = 0; i < decodedSamples; i++) {
                    mOut[i] *= mServerVolumeFactor;
                }
            }
            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);
"""
    if old_copy not in text:
        raise RuntimeError("could not locate final PCM mixer copy")
    before, marker, after = text.rpartition(old_copy)
    text = before + new_copy + after

    path.write_text(text, encoding="utf-8")


def patch_stable_transport(service_path: pathlib.Path) -> None:
    """Use the v0.2 transport behavior: force TCP on the active connection only."""
    service = service_path.read_text(encoding="utf-8")
    if "VC_FORCE_TCP_STABLE_TRANSPORT" not in service:
        service = replace_once(
            service,
            "            mConnection.setForceTCP(mForceTcp);\n",
            "            mConnection.setForceTCP(true); // VC_FORCE_TCP_STABLE_TRANSPORT\n",
            "Humla force-TCP configuration",
        )

    if "VC_CLIENT_RELEASE_ID" not in service:
        service = replace_once(
            service,
            "        version.setRelease(mClientName);\n",
            '        version.setRelease("VC Mumla v0.4 Stable Gain"); // VC_CLIENT_RELEASE_ID\n',
            "Mumble release string",
        )
    service_path.write_text(service, encoding="utf-8")

def patch_app_identity(root: pathlib.Path) -> None:
    beta_strings = root / "app/src/beta/res/values/strings_notranslate.xml"
    text = beta_strings.read_text(encoding="utf-8")
    text = text.replace(
        '<string name="app_name">Mumla Beta</string>',
        '<string name="app_name">VC Mumla</string>',
    )
    beta_strings.write_text(text, encoding="utf-8")


def validate(root: pathlib.Path) -> None:
    audio_output = (root / "libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java").read_text(encoding="utf-8")
    speech = (root / "libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.java").read_text(encoding="utf-8")
    service = (root / "libraries/humla/src/main/java/se/lublin/humla/HumlaService.java").read_text(encoding="utf-8")
    beta_strings = (root / "app/src/beta/res/values/strings_notranslate.xml").read_text(encoding="utf-8")

    checks = {
        "gain trailer parser": "VC_GAIN_TRAILER" in audio_output,
        "gain diagnostic": "VC_GAIN_DIAGNOSTIC" in audio_output and "VC-GAIN rx" in audio_output,
        "gain handoff": "addFrameToBuffer(dataBuffer, msgFlags, seq, vcServerGain)" in audio_output,
        "PCM gain": "VC_SERVER_GAIN_PCM" in speech,
        "per-packet gain": "vcGainByte" in speech and "vcUserData" in speech,
        "PCM multiply": "mOut[i] *= mServerVolumeFactor" in speech,
        "stable TCP tunnel": "VC_FORCE_TCP_STABLE_TRANSPORT" in service and "setForceTCP(true)" in service,
        "client release id": "VC Mumla v0.4 Stable Gain" in service,
        "custom app label": "VC Mumla" in beta_strings,
    }
    missing = [name for name, ok in checks.items() if not ok]
    if missing:
        raise RuntimeError("VC Mumla validation failed: " + ", ".join(missing))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    args = parser.parse_args()
    root = args.root.resolve()

    try:
        patch_audio_output(root / "libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java")
        patch_audio_output_speech(root / "libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.java")
        patch_stable_transport(root / "libraries/humla/src/main/java/se/lublin/humla/HumlaService.java")
        patch_app_identity(root)
        validate(root)
    except RuntimeError as exc:
        print(f"patch-mumla-vc-client: {exc}", file=sys.stderr)
        return 1

    print("VC Mumla patch: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
