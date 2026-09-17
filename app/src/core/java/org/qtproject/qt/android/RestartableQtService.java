package org.qtproject.qt.android;

/**
 * QtServiceBase lifecycle adapter for an embedded server that must be able to
 * stop and start again without killing the Android application process.
 *
 * Qt 6.8.3's QtServiceBase.onDestroy() ends with System.exit(0). That behavior
 * is appropriate for a standalone Qt service, but not for VC Mumble Server,
 * where the UI process must survive a server Stop operation.
 */
public class RestartableQtService extends QtServiceBase {
    @Override
    public void onDestroy() {
        // Do not call QtServiceBase.onDestroy(): upstream terminates the whole
        // Android process. QtNative.quitQt() performs the native termination,
        // resets the started flag, exits the Qt thread and clears its instance
        // so a later service start can create a fresh Qt thread.
        if (QtNative.getStateDetails().isStarted) {
            QtNative.quitQt();
        }
        QtNative.setService(null);
    }

    protected final boolean isQtRuntimeStarted() {
        return QtNative.getStateDetails().isStarted;
    }
}
