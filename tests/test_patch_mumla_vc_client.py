import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "patch-mumla-vc-client.py"


def main() -> int:
    with tempfile.TemporaryDirectory() as raw:
        root = pathlib.Path(raw)
        humla_root = root / "libraries/humla/src/main/java/se/lublin/humla"
        audio = humla_root / "audio"
        protocol = humla_root / "protocol"
        app_java = root / "app/src/main/java/se/lublin/mumla"
        app_app = app_java / "app"
        app_pref = app_java / "preference"
        app_xml = root / "app/src/main/res/xml"
        app_values = root / "app/src/main/res/values"
        beta = root / "app/src/beta/res/values"

        audio.mkdir(parents=True)
        protocol.mkdir(parents=True)
        app_java.mkdir(parents=True)
        app_app.mkdir(parents=True)
        app_pref.mkdir(parents=True)
        app_xml.mkdir(parents=True)
        app_values.mkdir(parents=True)
        beta.mkdir(parents=True)


        (root / "app/src/main/AndroidManifest.xml").parent.mkdir(parents=True, exist_ok=True)
        (root / "app/src/main/AndroidManifest.xml").write_text(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application />
</manifest>
""",
            encoding="utf-8",
        )

        (app_app / "MumlaActivity.java").write_text(
            """import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

class MumlaActivity {
    private Settings mSettings;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2;

    void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            if (mSettings.isFirstRun()) {
                showFirstRunGuide();
            } else {
                new StartupAction().execute(this);
            }
        }
    }

    private void showFirstRunGuide() {
        if (mSettings.isUsingCertificate()) {
            mSettings.setFirstRun(false);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setPositiveButton(R.string.generate, (DialogInterface dialog, int which) -> {
                    MumlaCertificateGenerateTask generateTask = new MumlaCertificateGenerateTask(MumlaActivity.this) {};
                    generateTask.execute();
                    mSettings.setFirstRun(false);
                })
                .show();
    }

    /**
     * Loads a fragment from the drawer.
     */
    private void loadDrawerFragment(int fragmentId) {
    }
}
""",
            encoding="utf-8",
        )

        (app_pref / "GeneralSettingsFragment.java").write_text(
            """package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;

import androidx.preference.Preference;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));
    }
}
""",
            encoding="utf-8",
        )

        (app_xml / "settings_general.xml").write_text(
            """<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <CheckBoxPreference
        android:key="stay_awake"
        android:title="@string/stay_awake"
        app:iconSpaceReserved="false" />
</PreferenceScreen>
""",
            encoding="utf-8",
        )

        (app_values / "strings.xml").write_text(
            """<resources>
    <string name="general">General</string>
</resources>
""",
            encoding="utf-8",
        )

        (app_java / "Settings.java").write_text(
            """import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;

class Settings {
    public static final String PREF_ECHO_CANCELLATION_METHOD = "echo_cancellation_method";
    public static final String DEFAULT_ECHO_CANCELLATION_METHOD = "none";
    private final SharedPreferences preferences;

    private Settings(Context ctx) {
        preferences = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public String getEchoCancellationMethod() {
        return preferences.getString(PREF_ECHO_CANCELLATION_METHOD, DEFAULT_ECHO_CANCELLATION_METHOD);
    }
}
""",
            encoding="utf-8",
        )

        (app_xml / "settings_audio.xml").write_text(
            """<PreferenceScreen>
        <ListPreference
            android:defaultValue="none"
            android:entries="@array/echoCancellationNames"
            android:entryValues="@array/echoCancellationValues"
            android:key="echo_cancellation_method"
            android:summary="@string/echoCancellationMethodSum"
            android:title="@string/echoCancellationMethod" />
</PreferenceScreen>
""",
            encoding="utf-8",
        )

        (audio / "AudioInput.java").write_text(
            """import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

class AudioInput {
    private static final String TAG = "AudioInput";
    private String mEchoCancellationMethod;
    private AcousticEchoCanceler aec;
    private AudioRecord mAudioRecord;

    private boolean enableEchoCancellation() {
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
}
""",
            encoding="utf-8",
        )

        (protocol / "AudioHandler.java").write_text(
            """class AudioHandler {
    private static final String TAG = "AudioHandler";
    private final AudioManager mAudioManager;
    private final int mAudioStream;
    private final int mAudioSource;
    private int mSampleRate;
    private int mBitrate;
    private int mFramesPerPacket;
    private IInputMode mInputMode;
    private float mAmplitudeBoost;
    private boolean mBluetoothOn;
    private boolean mHalfDuplex;
    private boolean mPreprocessorEnabled;
    private String mEchoCancellationMethod;
    private AudioEncodeListener mEncodeListener;
    private AudioOutput.AudioOutputListener mOutputListener;
    private boolean mTalking;
    private byte mTargetId;
    private Object mEncoderLock;
    private Context mContext;
    private HumlaLogger mLogger;
    private boolean mInitialized;

    public AudioHandler(Context context, HumlaLogger logger, int audioStream, int audioSource,
                        int sampleRate, int targetBitrate, int targetFramesPerPacket,
                        IInputMode inputMode, byte targetId, float amplitudeBoost,
                        boolean bluetoothEnabled, boolean halfDuplexEnabled,
                        boolean preprocessorEnabled, String echoCancellationMethod,
                        AudioEncodeListener encodeListener,
                        AudioOutput.AudioOutputListener outputListener) throws AudioInitializationException, NativeAudioException {
        mContext = context;
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

        mInput = new AudioInput(this, mAudioSource, mSampleRate, mEchoCancellationMethod);
        mOutput = new AudioOutput(mOutputListener);
    }

    /**
     * Shuts down the audio handler, halting input and output.
     */
    public synchronized void shutdown() {
        mInitialized = false;
        mBluetoothOn = false;

        mEncodeListener.onTalkingStateChanged(false);
    }
}
""",
            encoding="utf-8",
        )

        (audio / "AudioOutput.java").write_text(
            """import java.util.Arrays;
class AudioOutput {
    private ExecutorService mDecodeExecutorService;
    void x() {
            PacketBuffer dataBuffer = new PacketBuffer(pds.bufferBlock(pds.left()));
            aop.addFrameToBuffer(dataBuffer, msgFlags, seq);
    }
}
""",
            encoding="utf-8",
        )

        (audio / "AudioOutputSpeech.java").write_text(
            """class AudioOutputSpeech {
    private int mMissCount = 0;
    public void addFrameToBuffer(PacketBuffer pb, byte flags, int seq) {
                Speex.JitterBufferPacket packet = new Speex.JitterBufferPacket(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags);
                        ucFlags = jbp.getUserData();
            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);
    }
}
""",
            encoding="utf-8",
        )

        (humla_root / "HumlaService.java").write_text(
            """class HumlaService {
    void connect() {
            mConnection.setForceTCP(mForceTcp);
    }
    void version() {
        version.setRelease(mClientName);
    }
}
""",
            encoding="utf-8",
        )

        (beta / "strings_notranslate.xml").write_text(
            '<resources><string name="app_name">Mumla Beta</string></resources>',
            encoding="utf-8",
        )

        for run in range(2):
            result = subprocess.run(
                ["python3", str(SCRIPT), "--root", str(root)],
                text=True,
                capture_output=True,
                check=False,
            )
            assert result.returncode == 0, f"run {run + 1}: {result.stderr}"

        manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        mumla_activity = (app_app / "MumlaActivity.java").read_text(encoding="utf-8")
        general_fragment = (app_pref / "GeneralSettingsFragment.java").read_text(encoding="utf-8")
        general_xml = (app_xml / "settings_general.xml").read_text(encoding="utf-8")
        strings = (app_values / "strings.xml").read_text(encoding="utf-8")
        settings = (app_java / "Settings.java").read_text(encoding="utf-8")
        settings_audio = (app_xml / "settings_audio.xml").read_text(encoding="utf-8")
        audio_input = (audio / "AudioInput.java").read_text(encoding="utf-8")
        audio_handler = (protocol / "AudioHandler.java").read_text(encoding="utf-8")
        audio_output = (audio / "AudioOutput.java").read_text(encoding="utf-8")
        speech = (audio / "AudioOutputSpeech.java").read_text(encoding="utf-8")
        service = (humla_root / "HumlaService.java").read_text(encoding="utf-8")

        assert "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in manifest
        assert "VC_BATTERY_UNRESTRICTED_PERMISSION" in manifest
        assert "VC_BATTERY_UNRESTRICTED_PROMPT" in mumla_activity
        assert "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in mumla_activity
        assert "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS" in mumla_activity
        assert "PREF_VC_BATTERY_PROMPTED" in mumla_activity
        assert "VC_BATTERY_UNRESTRICTED_SETTINGS" in general_fragment
        assert "isIgnoringBatteryOptimizations" in general_fragment
        assert 'android:key="vc_battery_unrestricted"' in general_xml
        assert 'name="vc_battery_unrestricted_title"' in strings
        assert "อนุญาตให้ทำงานเบื้องหลัง" in strings
        assert "อนุญาตให้ VC Mumla ไม่ถูกจำกัดโดยระบบประหยัดแบตเตอรี่ของ Android" in strings
        assert "การตั้งค่าแบตเตอรี่" in strings

        assert 'DEFAULT_ECHO_CANCELLATION_METHOD = "system"' in settings
        assert "PREF_VC_AEC_MIGRATED" in settings
        assert "AcousticEchoCanceler.isAvailable()" in settings
        assert 'android:defaultValue="system"' in settings_audio

        assert "VC_AEC_DIAGNOSTIC" in audio_input
        assert "AudioEffect.SUCCESS" in audio_input
        assert "aec.getEnabled()" in audio_input
        assert "aec.hasControl()" in audio_input

        assert "VC_AEC_AUDIO_MODE" in audio_handler
        assert "AudioManager.MODE_IN_COMMUNICATION" in audio_handler
        assert "MediaRecorder.AudioSource.VOICE_COMMUNICATION" in audio_handler
        assert "restoreAudioMode" in audio_handler
        assert "mAudioManager.setMode(mVcPreviousAudioMode)" in audio_handler

        assert "VC_GAIN_TRAILER" in audio_output
        assert "VC_GAIN_DIAGNOSTIC" in audio_output
        assert "VC_SERVER_GAIN_PCM" in speech
        assert "VC_FORCE_TCP_STABLE_TRANSPORT" in service
        assert "VC Mumla v0.5 AEC" in service
        assert "VC Mumla" in (beta / "strings_notranslate.xml").read_text(encoding="utf-8")

    print("VC Mumla AEC patch fixture: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
