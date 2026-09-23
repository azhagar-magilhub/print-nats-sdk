package com.magilhub.printnats.desktop.render.compat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** android.content.res.AssetManager: assets are classpath resources under {@code .../desktop/render/assets/}. */
public final class AssetManager {
    static final String ROOT = "/com/magilhub/printnats/desktop/render/assets/";

    public InputStream open(String fileName) throws IOException {
        InputStream in = AssetManager.class.getResourceAsStream(ROOT + fileName);
        if (in == null) throw new FileNotFoundException(fileName);
        return in;
    }
}
