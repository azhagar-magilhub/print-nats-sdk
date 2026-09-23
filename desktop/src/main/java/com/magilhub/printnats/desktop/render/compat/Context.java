package com.magilhub.printnats.desktop.render.compat;

import java.io.File;

/** android.content.Context: just enough for the legacy renderer (assets, drawables, a files dir for logs). */
public class Context {
    private final AssetManager assets = new AssetManager();
    private final Resources resources = new Resources();
    private final File filesDir;

    public Context() {
        this(null);
    }

    /** @param filesDir where {@code getExternalFilesDir}/{@code getFilesDir} point (may be null: logging disabled). */
    public Context(File filesDir) {
        this.filesDir = filesDir;
    }

    public Context getApplicationContext() {
        return this;
    }

    public AssetManager getAssets() {
        return assets;
    }

    public Resources getResources() {
        return resources;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public File getExternalFilesDir(String type) {
        return filesDir;
    }
}
