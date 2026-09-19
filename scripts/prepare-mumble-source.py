#!/usr/bin/env python3
"""Prepare pinned Mumble 1.6.870 for VC Mumble Server on Android.

The patch stays intentionally narrow and reproducible:
- convert the Android server target to qt_add_executable so Qt 6.8+ can
  generate an AAR and own Java/native runtime deployment;
- normalize the server link/install declarations for Qt's Android module target;
- keep Mumble's ordinary main() entry point for QtService to launch;
- make event-loop shutdown return to Android instead of exit()ing;
- compile JNI control and proximity state into the same native library;
- gate regular-speech receiver additions through the VC proximity policy;
- preserve stock Mumble routing when proximity is disabled (the default).
"""

from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import sys

PATCH_MARKER = "VC Mumble Server Android embed patch"
PROXIMITY_INCLUDE = '#include "VCProximity.h"'


def replace_once(text: str, pattern: str, replacement: str, label: str, flags=re.MULTILINE) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"expected exactly one {label}; found {count}")
    return updated


def replace_literal_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one {label}; found {count}")
    return text.replace(old, new, 1)


def patch_android_compat(murmur: pathlib.Path) -> None:
    """Remove Linux-only assumptions that are not valid for an Android process."""
    unix_cpp = murmur / "UnixMurmur.cpp"
    if unix_cpp.is_file():
        text = unix_cpp.read_text(encoding="utf-8")
        text = text.replace(
            "#ifdef Q_OS_LINUX",
            "#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID)",
        )
        text = text.replace(
            "#if defined(Q_OS_LINUX)",
            "#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID)",
        )
        text = text.replace(
            "#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID) && !defined(Q_OS_ANDROID)",
            "#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID)",
        )
        unix_cpp.write_text(text, encoding="utf-8")

    main_cpp = murmur / "main.cpp"
    text = main_cpp.read_text(encoding="utf-8")
    if "VC Android syslog include" not in text:
        pattern = r"(?m)^(\s*#\s*include\s*<sys/syslog\.h>\s*)$"
        replacement = (
            "# ifdef Q_OS_ANDROID\n"
            "#  include <syslog.h> // VC Android syslog include\n"
            "# else\n"
            "#  include <sys/syslog.h>\n"
            "# endif"
        )
        text, count = re.subn(pattern, replacement, text, count=1)
        if count != 1:
            raise RuntimeError("could not patch syslog include for Android")
        main_cpp.write_text(text, encoding="utf-8")


def patch_android_unix_daemon(murmur: pathlib.Path) -> None:
    """Disable desktop Unix daemon/signal plumbing inside the Android service process."""
    unix_cpp = murmur / "UnixMurmur.cpp"
    if not unix_cpp.is_file():
        return

    text = unix_cpp.read_text(encoding="utf-8")
    if "VC_ANDROID_NO_UNIX_DAEMON" in text:
        return

    ctor = "UnixMurmur::UnixMurmur() {"
    if ctor not in text:
        raise RuntimeError("could not locate UnixMurmur constructor")
    text = replace_literal_once(
        text,
        ctor,
        ctor
        + "\n#ifdef Q_OS_ANDROID // VC_ANDROID_NO_UNIX_DAEMON"
        + "\n\tbRoot = false;"
        + "\n\tlogToSyslog = false;"
        + "\n\tqsnHup = nullptr;"
        + "\n\tqsnTerm = nullptr;"
        + "\n\tqsnUsr1 = nullptr;"
        + "\n\tiHupFd[0] = iHupFd[1] = -1;"
        + "\n\tiTermFd[0] = iTermFd[1] = -1;"
        + "\n\tiUsr1Fd[0] = iUsr1Fd[1] = -1;"
        + "\n\treturn;"
        + "\n#endif",
        "UnixMurmur Android constructor guard",
    )

    dtor = "UnixMurmur::~UnixMurmur() {"
    if dtor not in text:
        raise RuntimeError("could not locate UnixMurmur destructor")
    text = replace_literal_once(
        text,
        dtor,
        dtor
        + "\n#ifdef Q_OS_ANDROID"
        + "\n\treturn;"
        + "\n#endif",
        "UnixMurmur Android destructor guard",
    )

    setuid = "void UnixMurmur::setuid() {"
    if setuid not in text:
        raise RuntimeError("could not locate UnixMurmur::setuid")
    text = replace_literal_once(
        text,
        setuid,
        setuid
        + "\n#ifdef Q_OS_ANDROID"
        + "\n\treturn;"
        + "\n#endif",
        "UnixMurmur Android setuid guard",
    )

    unix_cpp.write_text(text, encoding="utf-8")


def patch_android_bootstrap_logging(murmur: pathlib.Path) -> None:
    """Persist native-main checkpoints before Murmur's normal logger is available."""
    main_cpp = murmur / "main.cpp"
    text = main_cpp.read_text(encoding="utf-8")

    if "VC_ANDROID_BOOTSTRAP_LOG" in text:
        return

    include_anchor = "#include <QSslSocket>"
    if include_anchor not in text:
        raise RuntimeError("could not locate QSslSocket include for bootstrap logging")
    text = replace_literal_once(
        text,
        include_anchor,
        include_anchor
        + "\n#include <cstdio>\n#include <cstdlib>\n#include <string>"
        + "\n#ifdef Q_OS_ANDROID"
        + "\nstatic void vcAndroidBootstrapLog(const char *message);"
        + "\n#endif",
        "Android bootstrap logging includes",
    )

    main_anchor = "int main(int argc, char **argv) {"
    if main_anchor not in text:
        raise RuntimeError("could not locate main for bootstrap logging")

    helper = r'''
#ifdef Q_OS_ANDROID
static void vcAndroidBootstrapLog(const char *message) { // VC_ANDROID_BOOTSTRAP_LOG
	const char *home = std::getenv("HOME");
	if (!home || !*home || !message)
		return;
	const std::string path = std::string(home) + "/vc-mumble-server.log";
	FILE *file = std::fopen(path.c_str(), "a");
	if (!file)
		return;
	std::fprintf(file, "[NATIVE-BOOT] %s\n", message);
	std::fflush(file);
	std::fclose(file);
}
#else
static void vcAndroidBootstrapLog(const char *) {}
#endif

'''
    text = replace_literal_once(
        text,
        main_anchor,
        helper + main_anchor + '\n\tvcAndroidBootstrapLog("main entered");',
        "Android bootstrap logger",
    )

    checkpoints = [
        ("ServerApplication a(argc, argv);",
         'vcAndroidBootstrapLog("creating ServerApplication");\n\t\tServerApplication a(argc, argv);\n\t\tvcAndroidBootstrapLog("ServerApplication created");'),
        ("MumbleSSL::initialize();",
         'vcAndroidBootstrapLog("initializing MumbleSSL");\n\t\tMumbleSSL::initialize();\n\t\tvcAndroidBootstrapLog("MumbleSSL initialized");'),
        ("CLIOptions cli_options = parseCLI(argc, argv);",
         'vcAndroidBootstrapLog("parsing CLI");\n\t\tCLIOptions cli_options = parseCLI(argc, argv);\n\t\tvcAndroidBootstrapLog("CLI parsed");'),
        ("if (QSslSocket::supportsSsl()) {",
         'vcAndroidBootstrapLog(QSslSocket::supportsSsl() ? "QSslSocket supports SSL" : "QSslSocket SSL unsupported");\n\t\tif (QSslSocket::supportsSsl()) {'),
        ("Meta::mp->read(inifile);",
         'vcAndroidBootstrapLog("reading Mumble ini");\n\t\tMeta::mp->read(inifile);\n\t\tvcAndroidBootstrapLog("Mumble ini read");'),
        ("MumbleSSL::addSystemCA();",
         'vcAndroidBootstrapLog("adding system CA");\n\t\tMumbleSSL::addSystemCA();\n\t\tvcAndroidBootstrapLog("system CA added");'),
        ("meta = new Meta(Meta::getConnectionParameter());",
         'vcAndroidBootstrapLog("creating Meta/database");\n\t\tmeta = new Meta(Meta::getConnectionParameter());\n\t\tvcAndroidBootstrapLog("Meta/database ready");'),
        ("meta->bootAll(Meta::getConnectionParameter(), true);",
         'vcAndroidBootstrapLog("bootAll begin");\n\t\tmeta->bootAll(Meta::getConnectionParameter(), true);\n\t\tvcAndroidBootstrapLog("bootAll complete");'),
        ("res = a.exec();",
         'vcAndroidBootstrapLog("entering Qt event loop");\n\t\tres = a.exec();\n\t\tvcAndroidBootstrapLog("Qt event loop returned");'),
    ]
    for old, new in checkpoints:
        if old in text:
            text = text.replace(old, new, 1)

    handler_anchor = "static void murmurMessageOutputQString(QtMsgType type, const QString &msg) {"
    if handler_anchor in text:
        text = replace_literal_once(
            text,
            handler_anchor,
            handler_anchor
            + '\n#ifdef Q_OS_ANDROID'
            + '\n\tvcAndroidBootstrapLog(qPrintable(msg));'
            + '\n#endif',
            "Android early Qt message logging",
        )

    main_cpp.write_text(text, encoding="utf-8")


def patch_embedded_lifecycle(murmur: pathlib.Path) -> None:
    main_cpp = murmur / "main.cpp"
    text = main_cpp.read_text(encoding="utf-8")

    # Qt's Android platform loader resolves the application entrypoint with
    # dlsym(mainLibraryHandle, "main"). Mumble builds with hidden visibility,
    # so explicitly export only main() for the Android shared-module target.
    if "VC_ANDROID_MAIN_EXPORT" not in text:
        text = replace_literal_once(
            text,
            "int main(int argc, char **argv) {",
            "#if defined(Q_OS_ANDROID) && defined(__GNUC__)\n"
            "__attribute__((visibility(\"default\"))) // VC_ANDROID_MAIN_EXPORT\n"
            "#endif\n"
            "int main(int argc, char **argv) {",
            "Android main entrypoint",
        )

    # Qt's Android runner calls exit(ret) after main() returns unless this
    # environment flag is set. The VC app must keep its Java UI process alive
    # across server startup failures and Stop/Start cycles.
    if "VC_ANDROID_NO_EXIT_CALL" not in text:
        text = replace_literal_once(
            text,
            "int main(int argc, char **argv) {",
            "int main(int argc, char **argv) {\n"
            "#ifdef Q_OS_ANDROID\n"
            "\tqputenv(\"QT_ANDROID_NO_EXIT_CALL\", \"1\"); // VC_ANDROID_NO_EXIT_CALL\n"
            "\tqputenv(\"ANDROID_OPENSSL_SUFFIX\", \"_3\"); // VC_ANDROID_OPENSSL_SUFFIX\n"
            "#endif",
            "Android no-exit guard",
        )

    if "VC_MUMBLE_EMBEDDED_RETURN" not in text:
        text = replace_once(
            text,
            r"(?m)^(\s*)exit\(signum\);\s*$",
            r"\1#ifdef VC_MUMBLE_EMBEDDED\n"
            r"\1\tQ_UNUSED(signum); // VC_MUMBLE_EMBEDDED_RETURN\n"
            r"\1\treturn;\n"
            r"\1#else\n"
            r"\1\texit(signum);\n"
            r"\1#endif",
            "cleanup exit(signum)",
        )

    if "VC_MUMBLE_EMBEDDED_SIGNALS" not in text:
        signal_pattern = (
            r"(?m)^(?P<indent>[ \t]*)signal\(SIGTERM, cleanup\);[ \t]*\n"
            r"(?P=indent)signal\(SIGINT, cleanup\);[ \t]*$"
        )
        match = re.search(signal_pattern, text)
        if not match:
            raise RuntimeError("could not locate standalone signal handlers")
        indent = match.group("indent")
        signal_block = (
            f"{indent}signal(SIGTERM, cleanup);\n"
            f"{indent}signal(SIGINT, cleanup);"
        )
        replacement = (
            f"{indent}#ifndef VC_MUMBLE_EMBEDDED // VC_MUMBLE_EMBEDDED_SIGNALS\n"
            + signal_block
            + f"\n{indent}#endif"
        )
        text = text[:match.start()] + replacement + text[match.end():]

    main_cpp.write_text(text, encoding="utf-8")


def patch_android_foreground_logfile(murmur: pathlib.Path) -> None:
    """Allow the Android foreground server to keep Mumble's own QFile logger active."""
    main_cpp = murmur / "main.cpp"
    text = main_cpp.read_text(encoding="utf-8")

    if "VC_ANDROID_FOREGROUND_LOGFILE" in text:
        return

    old = 'if (detach && !Meta::mp->qsLogfile.isEmpty() && !unixhandler.logToSyslog) {'
    if old not in text:
        raise RuntimeError("could not locate Mumble Unix logfile-open condition")

    replacement = (
        "#ifdef Q_OS_ANDROID // VC_ANDROID_FOREGROUND_LOGFILE\n"
        "\t\tif (!Meta::mp->qsLogfile.isEmpty() && !unixhandler.logToSyslog) {\n"
        "#else\n"
        "\t\t" + old + "\n"
        "#endif"
    )
    text = replace_literal_once(text, old, replacement, "Android foreground logfile condition")
    main_cpp.write_text(text, encoding="utf-8")


def patch_android_default_ini(murmur: pathlib.Path) -> None:
    """Use app-private config only when Mumble was not given an explicit -i/--ini."""
    main_cpp = murmur / "main.cpp"
    text = main_cpp.read_text(encoding="utf-8")

    if "VC_ANDROID_DEFAULT_INI" in text:
        return

    if "#include <QStandardPaths>" not in text:
        anchors = ['#include "ServerApplication.h"', '#include "Server.h"']
        for anchor in anchors:
            if anchor in text:
                text = replace_literal_once(
                    text,
                    anchor,
                    anchor + "\n#include <QStandardPaths>",
                    "Qt include anchor",
                )
                break
        else:
            raise RuntimeError("could not locate include anchor for QStandardPaths")

    pattern = (
        r"(?m)^(?P<indent>[ \t]*)"
        r"QString inifile(?:[ \t]*=[ \t]*[^;]+)?;[ \t]*$"
    )
    match = re.search(pattern, text)
    if not match:
        raise RuntimeError("could not locate Mumble ini-file assignment")

    indent = match.group("indent")
    original = match.group(0)
    replacement = (
        original
        + "\n#ifdef Q_OS_ANDROID // VC_ANDROID_DEFAULT_INI\n"
        + indent + "if (inifile.isEmpty()) {\n"
        + indent + "\tinifile = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)\n"
        + indent + "\t         + QLatin1String(\"/mumble/mumble-server.ini\");\n"
        + indent + "}\n"
        + "#endif"
    )
    text = text[:match.start()] + replacement + text[match.end():]
    main_cpp.write_text(text, encoding="utf-8")


def patch_server_routing(murmur: pathlib.Path) -> None:
    server_cpp = murmur / "Server.cpp"
    if not server_cpp.is_file():
        raise RuntimeError(f"required file not found: {server_cpp}")
    text = server_cpp.read_text(encoding="utf-8")

    if PROXIMITY_INCLUDE not in text:
        text = replace_literal_once(
            text,
            '#include "Server.h"',
            '#include "Server.h"\n#include "VCProximity.h"',
            "Server.h include",
        )

    if "VC_PROXIMITY_REGULAR_CHANNEL" in text:
        server_cpp.write_text(text, encoding="utf-8")
        return

    start_match = re.search(r"void\s+Server::processMsg\s*\(", text)
    if not start_match:
        raise RuntimeError("could not locate Server::processMsg")
    end_match = re.search(r"\n\s*ZoneNamedN\(", text[start_match.start():])
    if not end_match:
        raise RuntimeError("could not locate processMsg sendout boundary")

    segment_start = start_match.start()
    segment_end = segment_start + end_match.start()
    segment = text[segment_start:segment_end]

    guard_pattern = re.compile(r"if\s*\(pDst\)\s*\{")
    guards = list(guard_pattern.finditer(segment))
    if len(guards) != 2:
        raise RuntimeError(f"expected 2 regular listener guards in processMsg; found {len(guards)}")

    replacements = [
        "if (pDst && VCProximity::shouldRoute(u->qsName, pDst->qsName)) { // VC_PROXIMITY_REGULAR_LISTENER",
        "if (pDst && VCProximity::shouldRoute(u->qsName, pDst->qsName)) { // VC_PROXIMITY_LINKED_LISTENER",
    ]
    pieces = []
    cursor = 0
    for match, replacement in zip(guards, replacements):
        pieces.append(segment[cursor:match.start()])
        pieces.append(replacement)
        cursor = match.end()
    pieces.append(segment[cursor:])
    segment = "".join(pieces)

    normal_pattern = re.compile(
        r"(?P<indent>^[ \t]*)buffer\.addReceiver\(\*u,[ \t]*\*pDst,[ \t]*"
        r"Mumble::Protocol::AudioContext::NORMAL,[ \t]*"
        r"audioData\.containsPositionalData[ \t]*\);",
        re.MULTILINE,
    )
    normals = list(normal_pattern.finditer(segment))
    if len(normals) != 1:
        raise RuntimeError(f"expected 1 same-channel NORMAL receiver; found {len(normals)}")

    match = normals[0]
    indent = match.group("indent")
    original = match.group(0).lstrip(" \t")
    wrapped = (
        f"{indent}if (VCProximity::shouldRoute(u->qsName, pDst->qsName)) {{ // VC_PROXIMITY_REGULAR_CHANNEL\n"
        f"{indent}\t{original}\n"
        f"{indent}}}"
    )
    segment = segment[:match.start()] + wrapped + segment[match.end():]

    linked_pattern = re.compile(
        r"(?P<indent>^[ \t]*)buffer\.addReceiver\(\*u,\s*\*pDst,\s*"
        r"Mumble::Protocol::AudioContext::NORMAL,\s*\n"
        r"(?P<continuation>[ \t]*)audioData\.containsPositionalData\s*\);",
        re.MULTILINE,
    )
    linked = list(linked_pattern.finditer(segment))
    if len(linked) != 1:
        raise RuntimeError(f"expected 1 linked-channel NORMAL receiver; found {len(linked)}")

    match = linked[0]
    indent = match.group("indent")
    original_lines = match.group(0).lstrip(" \t").splitlines()
    indented_original = ("\n" + indent + "\t").join(original_lines)
    wrapped = (
        f"{indent}if (VCProximity::shouldRoute(u->qsName, pDst->qsName)) {{ // VC_PROXIMITY_LINKED_CHANNEL\n"
        f"{indent}\t{indented_original}\n"
        f"{indent}}}"
    )
    segment = segment[:match.start()] + wrapped + segment[match.end():]

    text = text[:segment_start] + segment + text[segment_end:]
    server_cpp.write_text(text, encoding="utf-8")


def patch_cmake(murmur: pathlib.Path) -> None:
    cmake = murmur / "CMakeLists.txt"
    cmake_text = cmake.read_text(encoding="utf-8")
    if PATCH_MARKER in cmake_text:
        return

    conditional_target = re.compile(
        r"if\(WIN32\)\s*\n"
        r"(?P<windent>[ \t]*)add_executable\(mumble-server\s+WIN32\s+(?P<sources>\$\{MURMUR_SOURCES\}|\"main\.cpp\")\)\s*\n"
        r"else\(\)\s*\n"
        r"(?P<uindent>[ \t]*)add_executable\(mumble-server\s+(?P=sources)\)\s*\n"
        r"endif\(\)",
        re.MULTILINE,
    )
    match = conditional_target.search(cmake_text)
    if not match:
        raise RuntimeError("could not locate mumble-server CMake target declaration")

    sources = match.group("sources")
    replacement = (
        "if(ANDROID)\n"
        f"\tqt_add_executable(mumble-server MANUAL_FINALIZATION {sources})\n"
        "elseif(WIN32)\n"
        f"{match.group('windent')}add_executable(mumble-server WIN32 {sources})\n"
        "else()\n"
        f"{match.group('uindent')}add_executable(mumble-server {sources})\n"
        "endif()"
    )
    cmake_text = cmake_text[:match.start()] + replacement + cmake_text[match.end():]

    # qt_add_executable() links Qt internally with the keyword signature. Mumble's
    # upstream server link is plain-signature, which CMake forbids mixing on the
    # same target. The prepared Android source is allowed to normalize this one
    # declaration to PRIVATE without changing the linked libraries.
    plain_server_link = "target_link_libraries(mumble-server mumble_server_object_lib CLI11::CLI11)"
    keyword_server_link = "target_link_libraries(mumble-server PRIVATE mumble_server_object_lib CLI11::CLI11)"
    if plain_server_link in cmake_text:
        cmake_text = replace_literal_once(
            cmake_text,
            plain_server_link,
            keyword_server_link,
            "mumble-server link signature",
        )
    elif keyword_server_link not in cmake_text:
        raise RuntimeError("could not normalize mumble-server target_link_libraries signature")

    # On Android qt_add_executable() represents the application as a shared/module
    # library. The desktop RUNTIME-only install rule is invalid for that target and
    # is unnecessary because this pipeline packages the server through Qt's AAR.
    desktop_install = 'install(TARGETS mumble-server RUNTIME DESTINATION "${MUMBLE_INSTALL_EXECUTABLEDIR}" COMPONENT mumble_server)'
    if desktop_install in cmake_text:
        cmake_text = replace_literal_once(
            cmake_text,
            desktop_install,
            "if(NOT ANDROID)\n\t" + desktop_install + "\nendif()",
            "desktop mumble-server install rule",
        )

    cmake_text += f"""

# {PATCH_MARKER}
if(ANDROID)
    # androiddeployqt requires the Android QPA platform plugin for application/AAR
    # deployment. That plugin depends on Qt Gui even though the Mumble server
    # itself remains QCoreApplication-based and headless at runtime.
    find_pkg(Qt6 COMPONENTS Gui REQUIRED)
    target_sources(mumble-server PRIVATE
        "${{CMAKE_CURRENT_LIST_DIR}}/AndroidEmbed.cpp"
        "${{CMAKE_CURRENT_LIST_DIR}}/AndroidJni.cpp"
        "${{CMAKE_CURRENT_LIST_DIR}}/VCProximity.cpp"
    )
    target_compile_definitions(mumble-server PRIVATE VC_MUMBLE_EMBEDDED=1)
    # The server is headless and does not consume QtGui symbols directly, but
    # androiddeployqt requires QtGui so it can deploy the Android QPA platform
    # plugin. Prevent lld from dropping QtGui via --as-needed.
    target_link_options(mumble-server PRIVATE "LINKER:--no-as-needed")
    target_link_libraries(mumble-server PRIVATE Qt6::Gui android log)
    set_target_properties(mumble-server PROPERTIES
        OUTPUT_NAME "vcserver"
        QT_ANDROID_MIN_SDK_VERSION 26
        QT_ANDROID_TARGET_SDK_VERSION 36
    )
    if(DEFINED VC_ANDROID_PACKAGE_SOURCE_DIR AND NOT "${{VC_ANDROID_PACKAGE_SOURCE_DIR}}" STREQUAL "")
        set_target_properties(mumble-server PROPERTIES
            QT_ANDROID_PACKAGE_SOURCE_DIR "${{VC_ANDROID_PACKAGE_SOURCE_DIR}}"
        )
    endif()
    if(DEFINED VC_ANDROID_EXTRA_LIBS AND NOT "${{VC_ANDROID_EXTRA_LIBS}}" STREQUAL "")
        set_target_properties(mumble-server PROPERTIES
            QT_ANDROID_EXTRA_LIBS "${{VC_ANDROID_EXTRA_LIBS}}"
        )
    endif()
    qt_finalize_target(mumble-server)
endif()
"""
    cmake.write_text(cmake_text, encoding="utf-8")


def prepare(
    source: pathlib.Path,
    adapter: pathlib.Path,
    jni: pathlib.Path,
    proximity_header: pathlib.Path,
    proximity_source: pathlib.Path,
) -> None:
    murmur = source / "src" / "murmur"
    cmake = murmur / "CMakeLists.txt"
    main_cpp = murmur / "main.cpp"
    server_cpp = murmur / "Server.cpp"

    for required in (cmake, main_cpp, server_cpp, adapter, jni, proximity_header, proximity_source):
        if not required.is_file():
            raise RuntimeError(f"required file not found: {required}")

    patch_cmake(murmur)

    shutil.copyfile(adapter, murmur / "AndroidEmbed.cpp")
    shutil.copyfile(jni, murmur / "AndroidJni.cpp")
    shutil.copyfile(proximity_header, murmur / "VCProximity.h")
    shutil.copyfile(proximity_source, murmur / "VCProximity.cpp")

    patch_android_compat(murmur)
    patch_android_unix_daemon(murmur)
    patch_android_bootstrap_logging(murmur)
    patch_embedded_lifecycle(murmur)
    patch_android_default_ini(murmur)
    patch_android_foreground_logfile(murmur)
    patch_server_routing(murmur)

    final_cmake = cmake.read_text(encoding="utf-8")
    final_main = main_cpp.read_text(encoding="utf-8")
    final_server = server_cpp.read_text(encoding="utf-8")
    checks = {
        "Qt Android executable target": "qt_add_executable(mumble-server MANUAL_FINALIZATION" in final_cmake,
        "vcserver output name": 'OUTPUT_NAME "vcserver"' in final_cmake,
        "Qt Android finalization": "qt_finalize_target(mumble-server)" in final_cmake,
        "Qt Android extra library handoff": "QT_ANDROID_EXTRA_LIBS" in final_cmake,
        "Qt Android package overlay handoff": "QT_ANDROID_PACKAGE_SOURCE_DIR" in final_cmake,
        "embedded compile definition": "VC_MUMBLE_EMBEDDED=1" in final_cmake,
        "Qt Android GUI deployment dependency": "Qt6::Gui" in final_cmake,
        "Qt Android GUI dependency retention": "LINKER:--no-as-needed" in final_cmake,
        "ordinary main retained": re.search(r"\bint\s+main\s*\(", final_main) is not None,
        "Android main exported for Qt loader": "VC_ANDROID_MAIN_EXPORT" in final_main,
        "Qt Android process exit disabled": "VC_ANDROID_NO_EXIT_CALL" in final_main,
        "Qt Android OpenSSL suffix": "VC_ANDROID_OPENSSL_SUFFIX" in final_main,
        "Android native bootstrap logging": "VC_ANDROID_BOOTSTRAP_LOG" in final_main,
        "Android default ini": "VC_ANDROID_DEFAULT_INI" in final_main,
        "Android foreground logfile": "VC_ANDROID_FOREGROUND_LOGFILE" in final_main,
        "embedded cleanup return": "VC_MUMBLE_EMBEDDED_RETURN" in final_main,
        "embedded signal guard": "VC_MUMBLE_EMBEDDED_SIGNALS" in final_main,
        "proximity include": PROXIMITY_INCLUDE in final_server,
        "regular routing hook": "VC_PROXIMITY_REGULAR_CHANNEL" in final_server,
        "linked routing hook": "VC_PROXIMITY_LINKED_CHANNEL" in final_server,
    }
    missing = [label for label, ok in checks.items() if not ok]
    if missing:
        raise RuntimeError("prepared source validation failed: " + ", ".join(missing))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--adapter", required=True, type=pathlib.Path)
    parser.add_argument("--jni", required=True, type=pathlib.Path)
    parser.add_argument("--proximity-header", required=True, type=pathlib.Path)
    parser.add_argument("--proximity-source", required=True, type=pathlib.Path)
    args = parser.parse_args()

    try:
        prepare(
            args.source.resolve(),
            args.adapter.resolve(),
            args.jni.resolve(),
            args.proximity_header.resolve(),
            args.proximity_source.resolve(),
        )
    except RuntimeError as exc:
        print(f"prepare-mumble-source: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
