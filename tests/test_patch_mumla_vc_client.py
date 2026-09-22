import pathlib
import tempfile
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "patch-mumla-vc-client.py"


def main() -> int:
    with tempfile.TemporaryDirectory() as raw:
        root = pathlib.Path(raw)
        audio = root / "libraries/humla/src/main/java/se/lublin/humla/audio"
        audio.mkdir(parents=True)
        beta = root / "app/src/beta/res/values"
        beta.mkdir(parents=True)

        (audio / "AudioOutput.java").write_text(
            """import java.util.Arrays;\nclass AudioOutput {\n"
            "void x() {\n"
            "            PacketBuffer dataBuffer = new PacketBuffer(pds.bufferBlock(pds.left()));\n"
            "            aop.addFrameToBuffer(dataBuffer, msgFlags, seq);\n"
            "}\n}\n""",
            encoding="utf-8",
        )
        (audio / "AudioOutputSpeech.java").write_text(
            """class AudioOutputSpeech {\n"
            "    private int mMissCount;\n"
            "    public void addFrameToBuffer(PacketBuffer pb, byte flags, int seq) {\n"
            "                Speex.JitterBufferPacket packet = new Speex.JitterBufferPacket(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags);\n"
            "                        ucFlags = jbp.getUserData();\n"
            "            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);\n"
            "}\n}\n""",
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
        assert "VC_SERVER_GAIN_PCM" in (audio / "AudioOutputSpeech.java").read_text()
        assert "VC Mumla" in (beta / "strings_notranslate.xml").read_text()

    print("VC Mumla patch fixture: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
