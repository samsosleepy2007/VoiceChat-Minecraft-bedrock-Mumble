package com.voicecraft.vcmumbleserver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small persisted runtime intent shared by the Activity and isolated Mumble
 * service process. Synchronous commits are intentional here: the service may
 * be created immediately after the user taps Start, so the desired state must
 * already be durable before Android launches :mumble.
 */
public final class ServerRuntimeState {
    private static final String PREFS = "vc_mumble_runtime";
    private static final String KEY_SHOULD_RUN = "server_should_run";

    private ServerRuntimeState() {}

    public static boolean shouldRun(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOULD_RUN, false);
    }

    public static void setShouldRun(Context context, boolean shouldRun) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putBoolean(KEY_SHOULD_RUN, shouldRun);
        editor.commit();
    }
}
