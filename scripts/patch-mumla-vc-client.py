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



def patch_aec_settings(settings_path: pathlib.Path, audio_xml_path: pathlib.Path) -> None:
    text = settings_path.read_text(encoding="utf-8")
    if "VC_AEC_DEFAULT_SYSTEM" not in text:
        text = replace_once(
            text,
            "import android.content.SharedPreferences;\nimport android.view.Gravity;\n",
            "import android.content.SharedPreferences;\n"
            "import android.media.audiofx.AcousticEchoCanceler;\n"
            "import android.view.Gravity;\n",
            "Settings AEC import",
        )
        text = replace_once(
            text,
            '    public static final String DEFAULT_ECHO_CANCELLATION_METHOD = "none";\n',
            '    public static final String DEFAULT_ECHO_CANCELLATION_METHOD = "system"; // VC_AEC_DEFAULT_SYSTEM\n'
            '    private static final String PREF_VC_AEC_MIGRATED = "vc_aec_migrated_v1";\n',
            "Settings AEC default",
        )
        text = replace_once(
            text,
            """    private Settings(Context ctx) {
        preferences = PreferenceManager.getDefaultSharedPreferences(ctx);
    }
""",
            """    private Settings(Context ctx) {
        preferences = PreferenceManager.getDefaultSharedPreferences(ctx);
        if (!preferences.getBoolean(PREF_VC_AEC_MIGRATED, false)) {
            SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(PREF_VC_AEC_MIGRATED, true);
            if (AcousticEchoCanceler.isAvailable()) {
                editor.putString(PREF_ECHO_CANCELLATION_METHOD, "system");
            }
            editor.apply();
        }
    }
""",
            "Settings one-time AEC migration",
        )
        text = replace_once(
            text,
            """    public String getEchoCancellationMethod() {
        return preferences.getString(PREF_ECHO_CANCELLATION_METHOD, DEFAULT_ECHO_CANCELLATION_METHOD);
    }
""",
            """    public String getEchoCancellationMethod() {
        String method = preferences.getString(
                PREF_ECHO_CANCELLATION_METHOD,
                DEFAULT_ECHO_CANCELLATION_METHOD
        );
        if ("system".equals(method) && !AcousticEchoCanceler.isAvailable()) {
            return "none";
        }
        return method;
    }
""",
            "Settings AEC fallback",
        )
        settings_path.write_text(text, encoding="utf-8")

    xml = audio_xml_path.read_text(encoding="utf-8")
    if 'android:key="echo_cancellation_method"' not in xml:
        raise RuntimeError("could not locate echo cancellation preference")
    old = """        <ListPreference
            android:defaultValue="none"
            android:entries="@array/echoCancellationNames"
"""
    new = """        <ListPreference
            android:defaultValue="system"
            android:entries="@array/echoCancellationNames"
"""
    if old in xml:
        xml = xml.replace(old, new, 1)
    elif new not in xml:
        raise RuntimeError("could not update echo cancellation preference default")
    audio_xml_path.write_text(xml, encoding="utf-8")


def patch_audio_input_aec(path: pathlib.Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "VC_AEC_DIAGNOSTIC" in text:
        return

    text = replace_once(
        text,
        "import android.media.audiofx.AcousticEchoCanceler;\n",
        "import android.media.audiofx.AcousticEchoCanceler;\n"
        "import android.media.audiofx.AudioEffect;\n",
        "AudioInput AudioEffect import",
    )

    old = """    private boolean enableEchoCancellation() {
        if (mEchoCancellationMethod.equals("system") /* android.media.audiofx.AcousticEchoCanceler */) {
            if (!AcousticEchoCanceler.isAvailable()) {
                Log.e(TAG, "could not enable system AEC: not available");
                return false;
            }
            if (aec != null) {
                aec.release();
            }
            aec = AcousticEchoCanceler.create(mAudioRecord.getAudioSessionId());
            if (aec == null) {
                Log.e(TAG, "could not enable system AEC: create failed");
                return false;
            }
            aec.setEnabled(true);
            return true;
        } else if (mEchoCancellationMethod.equals("none")) {
            Log.w(TAG, "echocancellation not enabled by user");
        } else {
            Log.w(TAG, "ignoring unknown echocancellation method: " + mEchoCancellationMethod);
        }
        return false;
    }
"""
    new = """    private boolean enableEchoCancellation() {
        if (mEchoCancellationMethod.equals("system") /* android.media.audiofx.AcousticEchoCanceler */) {
            boolean available = AcousticEchoCanceler.isAvailable();
            if (!available) {
                Log.e(TAG, "VC-AEC available=false method=system"); // VC_AEC_DIAGNOSTIC
                return false;
            }
            if (aec != null) {
                aec.release();
            }
            int sessionId = mAudioRecord.getAudioSessionId();
            aec = AcousticEchoCanceler.create(sessionId);
            if (aec == null) {
                Log.e(TAG, "VC-AEC available=true create=false session=" + sessionId);
                return false;
            }
            int status = aec.setEnabled(true);
            boolean enabled = aec.getEnabled();
            boolean hasControl = aec.hasControl();
            Log.i(TAG, "VC-AEC available=true create=true enabled=" + enabled
                    + " control=" + hasControl
                    + " status=" + status
                    + " session=" + sessionId
                    + " source=" + mAudioRecord.getAudioSource());
            if (status != AudioEffect.SUCCESS || !enabled) {
                aec.release();
                aec = null;
                return false;
            }
            return true;
        } else if (mEchoCancellationMethod.equals("none")) {
            Log.w(TAG, "VC-AEC method=none");
        } else {
            Log.w(TAG, "VC-AEC ignoring unknown method=" + mEchoCancellationMethod);
        }
        return false;
    }
"""
    text = replace_once(text, old, new, "AudioInput AEC implementation")
    path.write_text(text, encoding="utf-8")


def patch_audio_handler_aec(path: pathlib.Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "VC_AEC_AUDIO_MODE" in text:
        return

    text = replace_once(
        text,
        "    private final AudioManager mAudioManager;\n",
        "    private final AudioManager mAudioManager;\n"
        "    private int mVcPreviousAudioMode = AudioManager.MODE_NORMAL; // VC_AEC_AUDIO_MODE\n"
        "    private boolean mVcCommunicationModeActive = false;\n",
        "AudioHandler AEC mode fields",
    )

    old_ctor = """        mContext = context;
        mLogger = logger;
        mAudioStream = audioStream;
        mSampleRate = sampleRate;
        mBitrate = targetBitrate;
        mFramesPerPacket = targetFramesPerPacket;
        mInputMode = inputMode;
        mAmplitudeBoost = amplitudeBoost;
        mBluetoothOn = bluetoothEnabled;
        mHalfDuplex = halfDuplexEnabled;
        mPreprocessorEnabled = preprocessorEnabled;
        mEchoCancellationMethod = echoCancellationMethod;
        mEncodeListener = encodeListener;
        mOutputListener = outputListener;
        mTalking = false;
        mTargetId = targetId;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mEncoderLock = new Object();

        int actualSource = audioSource;
        if (echoCancellationMethod.equals("system") /* android.media.audiofx.AcousticEchoCanceler */) {
            // Enforce MODE_IN_COMMUNICATION for AudioManager, some AECs won't function without this.
            AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            actualSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
        mAudioSource = actualSource;
"""
    new_ctor = """        mContext = context;
        mLogger = logger;
        mSampleRate = sampleRate;
        mBitrate = targetBitrate;
        mFramesPerPacket = targetFramesPerPacket;
        mInputMode = inputMode;
        mAmplitudeBoost = amplitudeBoost;
        mBluetoothOn = bluetoothEnabled;
        mHalfDuplex = halfDuplexEnabled;
        mPreprocessorEnabled = preprocessorEnabled;
        mEchoCancellationMethod = echoCancellationMethod;
        mEncodeListener = encodeListener;
        mOutputListener = outputListener;
        mTalking = false;
        mTargetId = targetId;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mEncoderLock = new Object();

        int actualSource = audioSource;
        int actualStream = audioStream;
        if (echoCancellationMethod.equals("system") /* android.media.audiofx.AcousticEchoCanceler */) {
            // Android's system AEC expects a communication capture context.
            mVcPreviousAudioMode = mAudioManager.getMode();
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            mVcCommunicationModeActive = true;
            actualSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            Log.i(TAG, "VC-AEC audioMode=MODE_IN_COMMUNICATION"
                    + " previousMode=" + mVcPreviousAudioMode
                    + " inputSource=VOICE_COMMUNICATION"
                    + " outputStream=" + audioStream);
        }
        mAudioSource = actualSource;
        mAudioStream = actualStream;
"""
    text = replace_once(text, old_ctor, new_ctor, "AudioHandler communication audio mode")

    old_shutdown = """        mInitialized = false;
        mBluetoothOn = false;

        mEncodeListener.onTalkingStateChanged(false);
"""
    new_shutdown = """        mInitialized = false;
        mBluetoothOn = false;

        if (mVcCommunicationModeActive) {
            int currentMode = mAudioManager.getMode();
            if (currentMode == AudioManager.MODE_IN_COMMUNICATION) {
                mAudioManager.setMode(mVcPreviousAudioMode);
            }
            Log.i(TAG, "VC-AEC restoreAudioMode current=" + currentMode
                    + " restored=" + mVcPreviousAudioMode);
            mVcCommunicationModeActive = false;
        }

        mEncodeListener.onTalkingStateChanged(false);
"""
    text = replace_once(text, old_shutdown, new_shutdown, "AudioHandler AEC mode restore")
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
            '        version.setRelease("VC Mumla v0.5 AEC Test"); // VC_CLIENT_RELEASE_ID\n',
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
    settings = (root / "app/src/main/java/se/lublin/mumla/Settings.java").read_text(encoding="utf-8")
    settings_audio = (root / "app/src/main/res/xml/settings_audio.xml").read_text(encoding="utf-8")
    audio_input = (root / "libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java").read_text(encoding="utf-8")
    audio_handler = (root / "libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java").read_text(encoding="utf-8")

    checks = {
        "gain trailer parser": "VC_GAIN_TRAILER" in audio_output,
        "gain diagnostic": "VC_GAIN_DIAGNOSTIC" in audio_output and "VC-GAIN rx" in audio_output,
        "gain handoff": "addFrameToBuffer(dataBuffer, msgFlags, seq, vcServerGain)" in audio_output,
        "PCM gain": "VC_SERVER_GAIN_PCM" in speech,
        "per-packet gain": "vcGainByte" in speech and "vcUserData" in speech,
        "PCM multiply": "mOut[i] *= mServerVolumeFactor" in speech,
        "stable TCP tunnel": "VC_FORCE_TCP_STABLE_TRANSPORT" in service and "setForceTCP(true)" in service,
        "client release id": "VC Mumla v0.5 AEC Test" in service,
        "AEC default": "VC_AEC_DEFAULT_SYSTEM" in settings and 'DEFAULT_ECHO_CANCELLATION_METHOD = "system"' in settings,
        "AEC migration": "PREF_VC_AEC_MIGRATED" in settings,
        "AEC XML default": 'android:defaultValue="system"' in settings_audio,
        "AEC diagnostics": "VC_AEC_DIAGNOSTIC" in audio_input and "AudioEffect.SUCCESS" in audio_input,
        "communication mode": "VC_AEC_AUDIO_MODE" in audio_handler and "MODE_IN_COMMUNICATION" in audio_handler,
        "communication source": "VOICE_COMMUNICATION" in audio_handler,
        "audio mode restore": "restoreAudioMode" in audio_handler,
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
        patch_aec_settings(
            root / "app/src/main/java/se/lublin/mumla/Settings.java",
            root / "app/src/main/res/xml/settings_audio.xml",
        )
        patch_audio_input_aec(root / "libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java")
        patch_audio_handler_aec(root / "libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java")
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
