package com.magilhub.printnats.desktop.render.compat;

import java.awt.geom.AffineTransform;

/** android.graphics.Matrix subset (scale/translate/rotate with Android's pre/post semantics). */
public class Matrix {
    private AffineTransform t = new AffineTransform();

    public void reset() {
        t = new AffineTransform();
    }

    public boolean isIdentity() {
        return t.isIdentity();
    }

    public void setScale(float sx, float sy) {
        t = AffineTransform.getScaleInstance(sx, sy);
    }

    public void setTranslate(float dx, float dy) {
        t = AffineTransform.getTranslateInstance(dx, dy);
    }

    public boolean preScale(float sx, float sy) {
        t.scale(sx, sy);
        return true;
    }

    public boolean postScale(float sx, float sy) {
        t.preConcatenate(AffineTransform.getScaleInstance(sx, sy));
        return true;
    }

    public boolean preTranslate(float dx, float dy) {
        t.translate(dx, dy);
        return true;
    }

    public boolean postTranslate(float dx, float dy) {
        t.preConcatenate(AffineTransform.getTranslateInstance(dx, dy));
        return true;
    }

    public boolean postRotate(float degrees) {
        t.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(degrees)));
        return true;
    }

    public boolean preRotate(float degrees) {
        t.rotate(Math.toRadians(degrees));
        return true;
    }

    AffineTransform toAffine() {
        return new AffineTransform(t);
    }
}
