package com.magilhub.printnats.desktop.render.compat;

/** android.os.StrictMode: no-op (the legacy code relaxes it to fetch the logo URL on the render thread). */
public final class StrictMode {
    private StrictMode() {
    }

    public static final class ThreadPolicy {
        private ThreadPolicy() {
        }

        public static final class Builder {
            public Builder permitAll() {
                return this;
            }

            public ThreadPolicy build() {
                return new ThreadPolicy();
            }
        }
    }

    public static void setThreadPolicy(ThreadPolicy policy) {
    }
}
