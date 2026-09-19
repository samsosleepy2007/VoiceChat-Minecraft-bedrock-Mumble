package org.qtproject.qt.android;

import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.IBinder;

import com.voicecraft.vcmumbleserver.ServerLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Restartable Qt service wrapper for the embedded Mumble server.
 *
 * This class owns QtServiceBase's startup sequence so failures can be persisted
 * into the app's log instead of only going to Android logcat.
 */
public class RestartableQtService extends Service {
    private String lastQtStartupError = "";

    @Override
    public void onCreate() {
        super.onCreate();

        QtNative.setService(this);
        lastQtStartupError = "";

        try {
            logQtEnvironment();

            QtServiceLoader loader = QtServiceLoader.getServiceLoader(this);
            preflightQtLibraries();

            QtLoader.LoadingResult result = loader.loadQtLibraries();
            ServerLog.append(
                    this,
                    "QT",
                    "QtServiceLoader.loadQtLibraries => " + result
                            + ", mainLibraryPath=" + String.valueOf(loader.getMainLibraryPath())
            );

            if (result == QtLoader.LoadingResult.Failed) {
                lastQtStartupError = "QtServiceLoader failed to load Qt/native libraries";
                ServerLog.append(this, "QT-ERROR", lastQtStartupError);
                return;
            }

            // QtServiceBase only handles Succeeded. Treat AlreadyLoaded as a
            // valid restart state too and start the Qt application if needed.
            if (!QtNative.getStateDetails().isStarted) {
                String mainLibraryPath = loader.getMainLibraryPath();
                if (mainLibraryPath == null || mainLibraryPath.isEmpty()) {
                    lastQtStartupError = "Qt libraries loaded but main library path is empty";
                    ServerLog.append(this, "QT-ERROR", lastQtStartupError);
                    return;
                }

                ServerLog.append(
                        this,
                        "QT",
                        "Starting Qt application; main=" + mainLibraryPath
                                + ", args=" + loader.getApplicationParameters()
                );
                QtNative.startApplication(
                        loader.getApplicationParameters(),
                        mainLibraryPath
                );
                QtNative.setApplicationState(QtNative.ApplicationState.ApplicationHidden);
            }

            ServerLog.append(
                    this,
                    "QT",
                    "Qt startup complete; isStarted=" + QtNative.getStateDetails().isStarted
            );
        } catch (Throwable error) {
            lastQtStartupError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            ServerLog.append(this, "QT-ERROR", "Qt startup exception: " + lastQtStartupError);
        }
    }

    @Override
    public void onDestroy() {
        ServerLog.append(
                this,
                "QT",
                "RestartableQtService.onDestroy; isStarted=" + QtNative.getStateDetails().isStarted
        );

        if (QtNative.getStateDetails().isStarted) {
            QtNative.quitQt();
        }
        QtNative.setService(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        synchronized (this) {
            return QtNative.onBind(intent);
        }
    }

    protected final boolean isQtRuntimeStarted() {
        return QtNative.getStateDetails().isStarted;
    }

    protected final String qtStartupError() {
        return lastQtStartupError == null ? "" : lastQtStartupError;
    }

    private void logQtEnvironment() {
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        ServerLog.append(
                this,
                "QT",
                "Loader preflight; sdk=" + Build.VERSION.SDK_INT
                        + ", supportedAbis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                        + ", nativeLibraryDir=" + nativeDir
        );

        logResourceString("use_local_qt_libs");
        logResourceString("bundle_local_qt_libs");
        logResourceArray("qt_libs");
        logResourceArray("load_local_libs");
        logResourceArray("bundled_libs");
    }

    private void preflightQtLibraries() {
        String abi = preferredAbi();
        if (abi == null) {
            ServerLog.append(this, "QT-ERROR", "No supported ABI found in Qt resource arrays");
            return;
        }

        List<String> expected = new ArrayList<>();
        expected.addAll(librariesForAbi("qt_libs", abi));
        expected.addAll(librariesForAbi("load_local_libs", abi));
        expected.addAll(librariesForAbi("bundled_libs", abi));
        expected.add("vcserver_" + abi);

        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        for (String library : expected) {
            String fileName = library.endsWith(".so") ? library : "lib" + library + ".so";
            File candidate = new File(nativeDir, fileName);
            ServerLog.append(
                    this,
                    "QT-LIB",
                    fileName + " exists=" + candidate.isFile()
                            + ", bytes=" + (candidate.isFile() ? candidate.length() : 0)
            );
        }

        // Probe the same load order Qt uses. This both captures the exact
        // UnsatisfiedLinkError in log.txt and fixes dependency-order issues.
        for (String resourceName : new String[]{"qt_libs", "load_local_libs", "bundled_libs"}) {
            for (String library : librariesForAbi(resourceName, abi)) {
                probeSystemLoad(nativeDir, library, resourceName);
            }
        }
        probeSystemLoad(nativeDir, "vcserver_" + abi, "main");
    }

    private void probeSystemLoad(File nativeDir, String library, String source) {
        String fileName = library.endsWith(".so") ? library : "lib" + library + ".so";
        File candidate = new File(nativeDir, fileName);
        if (!candidate.isFile()) {
            ServerLog.append(this, "QT-ERROR", source + ": missing " + candidate.getAbsolutePath());
            return;
        }

        try {
            System.load(candidate.getAbsolutePath());
            ServerLog.append(this, "QT-LIB", source + ": System.load OK " + fileName);
        } catch (Throwable error) {
            ServerLog.append(
                    this,
                    "QT-ERROR",
                    source + ": System.load FAILED " + fileName + " => "
                            + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())
            );
        }
    }

    private String preferredAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (!librariesForAbi("qt_libs", abi).isEmpty()) return abi;
        }
        return null;
    }

    private List<String> librariesForAbi(String resourceName, String abi) {
        int id = getResources().getIdentifier(resourceName, "array", getPackageName());
        List<String> result = new ArrayList<>();
        if (id == 0) return result;

        try {
            for (String entry : getResources().getStringArray(id)) {
                String[] parts = entry.split(";", 2);
                if (parts.length == 2 && abi.equals(parts[0])) {
                    result.add(parts[1]);
                }
            }
        } catch (Resources.NotFoundException ignored) {
        }
        return result;
    }

    private void logResourceString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        if (id == 0) {
            ServerLog.append(this, "QT-RES", name + "=<missing>");
            return;
        }
        try {
            ServerLog.append(this, "QT-RES", name + "=" + getResources().getString(id));
        } catch (Resources.NotFoundException error) {
            ServerLog.append(this, "QT-RES", name + "=<not-found>");
        }
    }

    private void logResourceArray(String name) {
        int id = getResources().getIdentifier(name, "array", getPackageName());
        if (id == 0) {
            ServerLog.append(this, "QT-RES", name + "=<missing>");
            return;
        }
        try {
            ServerLog.append(
                    this,
                    "QT-RES",
                    name + "=" + Arrays.toString(getResources().getStringArray(id))
            );
        } catch (Resources.NotFoundException error) {
            ServerLog.append(this, "QT-RES", name + "=<not-found>");
        }
    }
}
