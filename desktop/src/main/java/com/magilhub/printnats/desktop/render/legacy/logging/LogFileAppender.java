// HAND-WRITTEN desktop replacement for android/.../legacy/logging/LogFileAppender (not produced by copy-from-android.sh).
package com.magilhub.printnats.desktop.render.legacy.logging;

import com.magilhub.printnats.desktop.render.compat.Context;
import com.magilhub.printnats.desktop.render.compat.Log;

/**
 * Where PrintUtil.appendLogInNativeComponent writes its {@code print_<date>.txt} lines on Android. On the desktop
 * the sidecar owns file logging, so this only forwards to {@link Log} (dropped unless a Log sink is installed).
 */
public final class LogFileAppender {
    private LogFileAppender() {
    }

    public static void append(Context context, String fileName, String content) {
        Log.i("LogFileAppender:" + fileName, content);
    }
}
