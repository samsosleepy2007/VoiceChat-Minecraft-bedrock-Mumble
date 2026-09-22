import pathlib
import tempfile
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "patch-mumla-vc-client.py"


def main() -> int:
    with tempfile.TemporaryDirectory() as raw:
        root = pathlib.Path(raw)
        humla_root = root / "libraries/humla/src/main/java/se/lublin/humla"
        audio = humla_root / "audio"
        net = humla_root / "net"
        audio.mkdir(parents=True)
        net.mkdir(parents=True)
        beta = root / "app/src/beta/res/values"
        beta.mkdir(parents=True)

        (audio / "AudioOutput.java").write_text(
            "import java.util.Arrays;\n"
            "class AudioOutput {\n"
            "    private ExecutorService mDecodeExecutorService;\n"
            "void x() {\n"
            "            PacketBuffer dataBuffer = new PacketBuffer(pds.bufferBlock(pds.left()));\n"
            "            aop.addFrameToBuffer(dataBuffer, msgFlags, seq);\n"
            "}\n}\n",
            encoding="utf-8",
        )
        (audio / "AudioOutputSpeech.java").write_text(
            "class AudioOutputSpeech {\n"
            "    private int mMissCount = 0;\n"
            "    public void addFrameToBuffer(PacketBuffer pb, byte flags, int seq) {\n"
            "                Speex.JitterBufferPacket packet = new Speex.JitterBufferPacket(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags);\n"
            "                        ucFlags = jbp.getUserData();\n"
            "            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);\n"
            "}\n}\n",
            encoding="utf-8",
        )
        (humla_root / "HumlaService.java").write_text(
            "class HumlaService {\n"
            "void connect() {\n"
            "            mConnection.setForceTCP(mForceTcp);\n"
            "}\n"
            "void version() {\n"
            "        version.setRelease(mClientName);\n"
            "}\n"
            "}\n",
            encoding="utf-8",
        )
        (beta / "strings_notranslate.xml").write_text(
            '<resources><string name="app_name">Mumla Beta</string></resources>',
            encoding="utf-8",
        )

        result = subprocess.run(
            ["python3", str(SCRIPT), "--root", str(root)],
            text=True, capture_output=True, check=False,
        )
        assert result.returncode == 0, result.stderr
        assert "VC_GAIN_TRAILER" in (audio / "AudioOutput.java").read_text()
        assert "VC_GAIN_DIAGNOSTIC" in (audio / "AudioOutput.java").read_text()
        assert "VC_SERVER_GAIN_PCM" in (audio / "AudioOutputSpeech.java").read_text()
        assert "VC Mumla" in (beta / "strings_notranslate.xml").read_text()
        assert "VC_FORCE_TCP_STABLE_TRANSPORT" in (humla_root / "HumlaService.java").read_text()
        assert "VC Mumla v0.4 Stable Gain" in (humla_root / "HumlaService.java").read_text()
        assert "VC_FORCE_TCP_HARD" in (net / "HumlaConnection.java").read_text()

    print("VC Mumla patch fixture: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
