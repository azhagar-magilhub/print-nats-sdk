package com.magilhub.printnats.desktop.render.compat;

import java.io.IOException;
import java.io.InputStream;

/** android.content.res.Resources: only raw drawable lookup by {@link R} id (classpath {@code .../render/res/drawable/}). */
public final class Resources {
    static final String DRAWABLE_ROOT = "/com/magilhub/printnats/desktop/render/res/drawable/";

    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String name) {
            super(name);
        }
    }

    public InputStream openRawResource(int id) {
        String file = R.fileFor(id);
        if (file == null) throw new NotFoundException("Resource ID #0x" + Integer.toHexString(id));
        InputStream in = Resources.class.getResourceAsStream(DRAWABLE_ROOT + file);
        if (in == null) throw new NotFoundException(file);
        return in;
    }

    static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
    }
}
