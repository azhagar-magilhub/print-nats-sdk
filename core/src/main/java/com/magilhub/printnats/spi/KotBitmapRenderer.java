package com.magilhub.printnats.spi;

import com.magilhub.printnats.render.KotLineDesc;

import java.util.List;

/**
 * Rasterises a KOT ticket the platform way — Android {@code Canvas} + the app's Outfit fonts, desktop Java2D
 * through the {@code render.compat} shim. Only Template 6 uses it: thermal text mode has nothing between
 * Font A 1x1 and Font B 2x2, and the sizes MerchantApp settled on for T6 sit in that gap, so T6 prints as one
 * bitmap (MerchantApp {@code PrintUtil.renderT6KotBitmap}). Templates 1–5 stay firmware text.
 *
 * <p>The lines carry their role in {@link KotLineDesc#size} ({@code T6_ROLE_*}); the implementation maps each
 * role to a pixel size and typeface. Returning {@code null} (or leaving no renderer configured) makes
 * {@code ThermalKotRenderer} fall back to Template 6's text mode, so a host without a rasteriser still prints.
 */
public interface KotBitmapRenderer {
    /**
     * @param lines  the ticket, roles in {@link KotLineDesc#size}
     * @param is58mm 58 mm paper (432 px) instead of 80 mm (576 px)
     * @return the ESC/POS image command stream to write as-is, or {@code null} to fall back to text mode
     */
    byte[] render(List<KotLineDesc> lines, boolean is58mm);
}
