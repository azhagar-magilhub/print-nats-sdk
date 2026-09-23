package com.magilhub.printnats.desktop.render.compat;

/** Stand-in for the Android library's generated R: drawable ids → bundled files in {@code .../render/res/drawable/}. */
public final class R {
    private R() {
    }

    public static final class drawable {
        public static final int pm_amex = 0x7f080001;
        public static final int pm_applepay = 0x7f080002;
        public static final int pm_discover = 0x7f080003;
        public static final int pm_googlepay = 0x7f080004;
        public static final int pm_mastercard = 0x7f080005;
        public static final int pm_visa = 0x7f080006;
        public static final int smalllogo = 0x7f080007;

        private drawable() {
        }
    }

    static String fileFor(int id) {
        switch (id) {
            case drawable.pm_amex: return "pm_amex.png";
            case drawable.pm_applepay: return "pm_applepay.png";
            case drawable.pm_discover: return "pm_discover.png";
            case drawable.pm_googlepay: return "pm_googlepay.png";
            case drawable.pm_mastercard: return "pm_mastercard.png";
            case drawable.pm_visa: return "pm_visa.png";
            case drawable.smalllogo: return "smalllogo.jpg";
            default: return null;
        }
    }
}
