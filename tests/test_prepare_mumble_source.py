import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "prepare-mumble-source.py"
ADAPTER = ROOT / "native" / "mumble_android" / "AndroidEmbed.cpp"
JNI = ROOT / "app" / "src" / "main" / "cpp" / "mumble_jni.cpp"
PROX_H = ROOT / "native" / "mumble_android" / "VCProximity.h"
PROX_CPP = ROOT / "native" / "mumble_android" / "VCProximity.cpp"


def run_prepare(source: pathlib.Path):
    return subprocess.run(
        [
            "python3", str(SCRIPT),
            "--source", str(source),
            "--adapter", str(ADAPTER),
            "--jni", str(JNI),
            "--proximity-header", str(PROX_H),
            "--proximity-source", str(PROX_CPP),
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def server_fixture() -> str:
    return r'''#include "Server.h"
void Server::processMsg(ServerUser *u, Mumble::Protocol::AudioData audioData, AudioReceiverBuffer &buffer,
                        Mumble::Protocol::UDPAudioEncoder< Mumble::Protocol::Role::Server > &encoder) {
	if (regularSpeech) {
		Channel *c = u->cChannel;
		for (unsigned int currentSession : m_channelListenerManager.getListenersForChannel(c->iId)) {
			ServerUser *pDst = static_cast< ServerUser * >(qhUsers.value(currentSession));
			if (pDst) {
				buffer.addReceiver(*u, *pDst, Mumble::Protocol::AudioContext::LISTEN, audioData.containsPositionalData,
								   m_channelListenerManager.getListenerVolumeAdjustment(pDst->uiSession, c->iId));
			}
		}
		for (User *p : c->qlUsers) {
			ServerUser *pDst = static_cast< ServerUser * >(p);
			buffer.addReceiver(*u, *pDst, Mumble::Protocol::AudioContext::NORMAL, audioData.containsPositionalData);
		}
		for (Channel *l : chans) {
			if (canSpeak) {
				for (unsigned int currentSession : m_channelListenerManager.getListenersForChannel(l->iId)) {
					ServerUser *pDst = static_cast< ServerUser * >(qhUsers.value(currentSession));
					if (pDst) {
						buffer.addReceiver(*u, *pDst, Mumble::Protocol::AudioContext::LISTEN,
									   audioData.containsPositionalData,
									   m_channelListenerManager.getListenerVolumeAdjustment(pDst->uiSession, l->iId));
					}
				}
				for (User *p : l->qlUsers) {
					ServerUser *pDst = static_cast< ServerUser * >(p);
					buffer.addReceiver(*u, *pDst, Mumble::Protocol::AudioContext::NORMAL,
									   audioData.containsPositionalData);
				}
			}
		}
	}
	ZoneNamedN(test_zone, sendout, true);
}
'''


def main_fixture() -> str:
    return r'''#include "ServerApplication.h"
#include <QSslSocket>
#ifdef Q_OS_WIN
#else
#	include <fcntl.h>
#	include <sys/syslog.h>
#endif
void cleanup(int signum) {
	exit(signum);
}
int main(int argc, char **argv) {
	QString inifile = QString::fromStdString(cli_options.iniFile.value_or(""));
		signal(SIGTERM, cleanup);
		signal(SIGINT, cleanup);
	int res = a.exec();
	cleanup(res);
	return res;
}
'''


def main() -> int:
    with tempfile.TemporaryDirectory() as raw:
        source = pathlib.Path(raw) / "mumble-1.6.870"
        murmur = source / "src" / "murmur"
        murmur.mkdir(parents=True)
        (murmur / "CMakeLists.txt").write_text(
            'if(WIN32)\n'
            '\tadd_executable(mumble-server WIN32 "main.cpp")\n'
            'else()\n'
            '\tadd_executable(mumble-server "main.cpp")\n'
            'endif()\n'
            'target_link_libraries(mumble-server mumble_server_object_lib CLI11::CLI11)\n',
            encoding="utf-8",
        )
        (murmur / "main.cpp").write_text(main_fixture(), encoding="utf-8")
        (murmur / "Server.cpp").write_text(server_fixture(), encoding="utf-8")
        (murmur / "UnixMurmur.cpp").write_text(
            "#ifdef Q_OS_LINUX\n# include <sys/capability.h>\n#endif\n"
            "#if defined(Q_OS_LINUX)\nint linux_only = 1;\n#endif\n",
            encoding="utf-8",
        )

        first = run_prepare(source)
        assert first.returncode == 0, first.stderr

        cmake = (murmur / "CMakeLists.txt").read_text(encoding="utf-8")
        main_cpp = (murmur / "main.cpp").read_text(encoding="utf-8")
        server_cpp = (murmur / "Server.cpp").read_text(encoding="utf-8")
        unix_cpp = (murmur / "UnixMurmur.cpp").read_text(encoding="utf-8")

        assert 'if(ANDROID)\n\tqt_add_executable(mumble-server MANUAL_FINALIZATION "main.cpp")' in cmake
        assert 'add_executable(mumble-server WIN32 "main.cpp")' in cmake
        assert 'add_executable(mumble-server "main.cpp")' in cmake
        assert "AndroidEmbed.cpp" in cmake
        assert "AndroidJni.cpp" in cmake
        assert "VCProximity.cpp" in cmake
        assert "VC_MUMBLE_EMBEDDED=1" in cmake
        assert 'OUTPUT_NAME "vcserver"' in cmake
        assert "QT_ANDROID_EXTRA_LIBS" in cmake
        assert "VC_ANDROID_EXTRA_LIBS" in cmake
        assert "VC_MUMBLE_EMBEDDED_RETURN" in main_cpp
        assert "VC_MUMBLE_EMBEDDED_SIGNALS" in main_cpp
        assert "exit(signum);" in main_cpp
        assert '#include "VCProximity.h"' in server_cpp
        assert "VC_PROXIMITY_REGULAR_LISTENER" in server_cpp
        assert "VC_PROXIMITY_REGULAR_CHANNEL" in server_cpp
        assert "VC_PROXIMITY_LINKED_LISTENER" in server_cpp
        assert "VC_PROXIMITY_LINKED_CHANNEL" in server_cpp
        assert (murmur / "AndroidEmbed.cpp").is_file()
        assert (murmur / "AndroidJni.cpp").is_file()
        assert "VC_ANDROID_DEFAULT_INI" in main_cpp
        assert "QStandardPaths" in main_cpp
        assert 'QString inifile = QString::fromStdString(cli_options.iniFile.value_or(""));' in main_cpp
        assert (murmur / "VCProximity.h").is_file()
        assert (murmur / "VCProximity.cpp").is_file()
        assert "VC Android syslog include" in main_cpp
        assert "!defined(Q_OS_ANDROID)" in unix_cpp

        cmake_once = cmake
        main_once = main_cpp
        server_once = server_cpp
        unix_once = unix_cpp

        second = run_prepare(source)
        assert second.returncode == 0, second.stderr
        assert (murmur / "CMakeLists.txt").read_text(encoding="utf-8") == cmake_once
        assert (murmur / "main.cpp").read_text(encoding="utf-8") == main_once
        assert (murmur / "Server.cpp").read_text(encoding="utf-8") == server_once
        assert (murmur / "UnixMurmur.cpp").read_text(encoding="utf-8") == unix_once

    print("prepare-mumble-source 1.6.870 fixture test: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
