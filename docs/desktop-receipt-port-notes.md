# Desktop receipt / EOD rendering (Java2D port)

`com.magilhub.printnats.desktop.render.Java2dReceiptRenderer implements spi.ReceiptRenderer` is the desktop twin of
the Android adapter's `LegacyReceiptRenderer`. It runs **MerchantApp's own receipt/EOD code** (not a rewrite) on a
small Java2D implementation of the Android graphics API, and captures the ESC/POS bytes that code writes.

Same job payloads as Android:

| JobKind | payload | output |
|---|---|---|
| RECEIPT | `{"receiptJson": "<ReceiptPojo json>", "textReceipt": false}` | `LF`, `GS v 0` raster (432 dots on 58 mm / 576 on 80 mm), `GS V 1` — layout `receipt-image` |
| RECEIPT | `{"receiptJson": "<Receipt json>", "textReceipt": true}` | ESC/POS text (`GS ! 0`, `ESC a`, …, `GS V 1`) — layout `receipt-text` |
| EOD | `{"eodJson": "<EodReport json>"}` or `{"itemReportsJson": "[…]"}` | `GS v 0` raster (383 / 631 dots), `GS V 1` — layout `eod` |

(Desktop also accepts `eodJson` / `itemReportsJson` as embedded JSON instead of a string; Android requires a string.)
Renders are serialised on a static lock (PrintUtil keeps `mContext`, `mis58mm` in static fields).

## Layout

```
desktop/src/main/java/com/magilhub/printnats/desktop/render/
  Java2dReceiptRenderer.java   hand-written (mirrors android/render/LegacyReceiptRenderer)
  EscPosPreview.java           hand-written: GS v 0 → BufferedImage, ESC/POS → plain text (previews/tests)
  compat/                      hand-written Android-API shim on Java2D
  compat/json/                 hand-written org.json (Android/libcore semantics) over Gson
  legacy/                      GENERATED copy (66 files) — do not edit
  legacy/copy-from-android.sh  the generator
  legacy/logging/LogFileAppender.java   hand-written (only non-generated file under legacy/)
desktop/src/main/resources/com/magilhub/printnats/desktop/render/
  assets/fonts/**              = android/src/main/assets/fonts (Outfit-*.ttf, bold.ttf, regular.ttf)
  res/drawable/**              = android/src/main/res/drawable (pm_*.png, smalllogo.jpg)
  NOTICE-dantsu-escpos.txt     DantSu ESC/POS (MIT) notice
```

## What was copied vs hand-written

**Copied verbatim** (by script) from `android/src/main/java/com/magilhub/printnats/android/legacy/` — which is itself
the package-renamed MerchantApp code: `framework/ConnectionUtils/PrintUtil.java` (whole file, incl. KOT code which is
unused here), `framework/models/*` (minus `StarStatusDTO`), `framework/pojo/ReceiptPojo`, `framework/async/AsyncEscPosPrinter`,
`framework/utility/{Table,Utils}`, `framework/utility/drawreceipt/*` (ReceiptBuilder for EOD), and DantSu
`escpos/{EscPosPrinter,EscPosPrinterCommands,EscPosPrinterSize,EscPosCharsetEncoding}`, `escpos/{barcode,exceptions,textparser}/*`,
`escpos/connection/DeviceConnection`.

So dithering/threshold (`convertToBlackAndWhite`, `ditherToBW`, `convertToBlackAndWhiteDithered`), QR generation
(`generateQr`, zxing), the payment-method strip, and the raster encoder (`EscPosPrinterCommands.bitmapToBytes`,
`EscPosPrinterSize.bitmapToBytes`, `printImage`) are the original code running over `compat.Bitmap`'s int pixels —
not re-implementations.

**Script rewrites only** (every edit is textual and listed here — no hand edits to copied code):
1. package `com.magilhub.printnats.android.legacy` → `com.magilhub.printnats.desktop.render.legacy`
2. `com.magilhub.printnats.android.R` → `…render.compat.R` (6 `R.drawable.pm_*` refs in `buildPaymentMethodsStrip`)
3. `android.graphics.*`, `android.graphics.drawable.*`, `android.content.*`, `android.util.*`, `android.text.*`,
   `android.os.*` → `…render.compat.*` (imports and fully-qualified uses, e.g. `android.graphics.DashPathEffect`,
   `android.os.StrictMode`)
4. `androidx.annotation.*` → `…render.compat.*`; `org.json.*` → `…render.compat.json.*`
5. deleted two unused imports in PrintUtil: `androidx.room.FtsOptions`, `…db.entity.PrintEntity`
6. header comment lines 1–2 of MerchantApp-origin files replaced with a "GENERATED" note

Not copied: `escpos/connection/{bluetooth,usb,tcp}` (Android transports), `StarStatusDTO` (Star SDK),
`db/entity/PrintEntity` (Room), `logging/LogFileAppender` (replaced by a forwarder to `compat.Log`).

**Hand-written:** `Java2dReceiptRenderer`, `EscPosPreview`, `legacy/logging/LogFileAppender`, everything in `compat/`.

## Regenerating the copy

When `android/.../legacy` is re-copied from MerchantApp:

```bash
bash desktop/src/main/java/com/magilhub/printnats/desktop/render/legacy/copy-from-android.sh
JAVA_HOME=~/.jenv/versions/11 ./gradlew :desktop:test
```

If the new code uses an Android API the shim lacks, compilation fails — add the member to `compat/` (never edit
`legacy/`). Also re-copy `android/src/main/{assets/fonts,res/drawable}` into `desktop/src/main/resources/…/render/`
if assets change, and add new drawable ids to `compat/R.java`.

## Compat layer coverage

| Android class | Java2D backing / notes |
|---|---|
| `Bitmap` | `BufferedImage` **TYPE_INT_ARGB_PRE** (Skia stores/filters premultiplied); `getPixel/setPixel` are unpremultiplied ARGB like Android. `RGB_565` → `TYPE_INT_RGB` (no 5/6/5 quantisation; only used for the black/white QR). `createBitmap` (size / crop / matrix+filter / int[]), `createScaledBitmap` (bilinear if `filter`, else nearest), `copy`, `compress` (ImageIO), `eraseColor`, `get/setPixels`. `recycle()` only flags (pixels stay readable). |
| `Canvas` | one `Graphics2D`; `drawColor` (SRC_OVER, ignores matrix), `drawText` (y = baseline), `drawLine(s)` (always stroked), `drawRect/RoundRect/Circle/Oval` by Style, `drawBitmap` (x/y, src/dst Rect(F), Matrix; Paint alpha, FILTER_BITMAP → bilinear else nearest, ColorFilter), save/restore/translate/scale/rotate/clipRect. |
| `Paint` | Android defaults (AA **off**, size 12, BLACK, FILL, width 0 = hairline, BUTT/MITER). Text: size in px; Align shifts x by `measureText`; kerning + ligatures on; `SUBPIXEL_TEXT_FLAG` → fractional advances (else integer/hinted); letter spacing (em, `TextAttribute.TRACKING`, half-spacing lead-in like Minikin); STROKE / FILL_AND_STROKE on glyph outlines; fake-bold with Skia's outset table; underline/strike. `measureText`, `breakText`, `getTextWidths`, `getTextBounds` (ink bounds rounded out), `ascent/descent/getFontMetrics(Int)/getFontSpacing`. `DashPathEffect` → dashed `BasicStroke`. `STROKE_PURE`. Missing glyphs fall back per run to Java `SansSerif`. |
| `Typeface` | `createFromAsset` (classpath `…/render/assets/`, cached per path), `createFromFile`, `create`, `defaultFromStyle`, DEFAULT/DEFAULT_BOLD/SANS_SERIF/SERIF/MONOSPACE (→ Java logical fonts). Metrics of bundled fonts are parsed from the TTF (`TtfMetrics`: hhea, or OS/2 typo with USE_TYPO_METRICS; head bbox for top/bottom) — same source Skia/FreeType uses — so every `-paint.ascent()` baseline in PrintUtil/DrawText lands where it does on Android. |
| `BitmapFactory` | ImageIO; `decodeStream/ByteArray/Resource/File`, Options (`inJustDecodeBounds`, `inSampleSize`, `out*`); no density scaling (legacy passes `inScaled=false` anyway). |
| `Color`, `Rect`, `RectF`, `Matrix`, `ColorMatrix`, `ColorMatrixColorFilter`, `ColorFilter`, `PathEffect`, `DashPathEffect` | same math |
| `Context`, `AssetManager`, `Resources`, `R` | assets/drawables from the classpath; `R.drawable` ids are stand-ins |
| `Log`, `TextUtils`, `Pair`, `StrictMode`, `Drawable`, `BitmapDrawable`, `Nullable`, `NonNull` | trivial; `Log` drops everything unless `Log.setSink(...)` or `-Dprintnats.legacyLog=true` |
| `org.json` (`JSONObject`, `JSONArray`, `JSONException`) | libcore coercions (`getString` on a number → `"12"`, on null → `"null"`; `getDouble("3.5")` works; ints/longs/doubles as libcore's tokenizer types them) |

## Known rendering differences vs Android

The byte *structure* (commands, raster widths, layout coordinates, text wrapping) matches; individual raster dots can
differ slightly because the rasterisers differ:

- **Glyph rasterisation / hinting.** Android draws with Skia+FreeType (slight hinting, Skia AA coverage); Java2D uses
  its own scaler (T2K/FreeType depending on JDK) and coverage. Edges of glyphs differ by ~1 px after the 127 threshold;
  stem weights can look a hair lighter/heavier. Non-AA text (EOD `DrawText` paints) uses hinted mono rendering on both
  sides but with different hinters.
- **Advances.** With `setSubpixelText(true)` (all image-receipt paints) both use fractional unhinted advances, so
  `measureText`-driven wrapping/right-alignment should match; tiny cumulative differences can move a wrap point in
  rare edge cases. Without subpixel (EOD), Java uses hinted integer advances; Android's may differ by rounding.
- **Kerning/ligatures.** Java's layout engine (HarfBuzz on JDK 11+, ICU on Java 8) vs Minikin/HarfBuzz — same OpenType
  data, should match; letter-spaced text disables ligatures on both.
- **Font fallback.** Characters missing from Outfit (e.g. `☐` in the tip slip, `₹`) come from Java `SansSerif`
  (a JRE/OS font — differs between Windows XP / Windows 10 / macOS) instead of Android's Roboto/Noto.
- **Bitmap filtering.** Bilinear on both; Java2D edge handling and rounding in `createScaledBitmap` (receipt canvas
  449/609 px → 432/576, EOD 1000 → 383/631) differ in the last bit, which can flip dots right at the 127/160
  thresholds.
- **Logo download.** `businessDetails.logo` is fetched synchronously from its URL, exactly as on Android (StrictMode
  is a no-op); a slow/unreachable URL delays or skips the logo the same way.
- **Timeouts/sleeps.** The text path's `Thread.sleep(500)` is kept (renders ≥ 0.5 s); DantSu `send()` pacing sleeps
  are skipped, as on Android's capture connection.
- `Bitmap.recycle()` doesn't free; drawing a recycled bitmap would throw on Android but silently works here.
- Legacy behaviours that are **not** port differences (visible in the previews): the 58 mm tip slip clips the long
  "cardholder agrees…/PLEASE SIGN…" lines at both edges, and the 58 mm text receipt truncates the double-width
  restaurant name to 16 chars — confirm against an Android print, but the copied code does the same thing.

## Tests and previews

`JAVA_HOME=~/.jenv/versions/11 ./gradlew :desktop:test` (headless) runs
`render/Java2dReceiptRendererTest` (full receipt with modifiers/notes/totals+tip/pay-QR+card strip/card payment/review
QR; EOD tip-suggestion slip; split-payment transaction slip; text receipt; EOD report; EOD item report — each 58 & 80 mm)
and `render/CompatLayerTest` (metrics, baseline/alignment, breakText, premultiplication, drawables, DantSu raster
threshold, org.json coercion). Previews land in `desktop/build/receipt-previews/`:
`*-printed.png` = the raster decoded back from the ESC/POS bytes (exactly what the printer gets),
`*-canvas.png` = the anti-aliased canvas before scaling/threshold, `receipt-text-*.txt` = text receipt with commands
stripped.

## Verifying on a real printer

1. Pick one order and print it from the Android app (MerchantApp / sample-android) on an 80 mm and a 58 mm printer.
2. Render the same payload on desktop and send the bytes raw (port 9100 / Windows RAW queue), e.g. with the sidecar's
   test endpoint or `nc printer 9100 < out.bin` after dumping `RenderResult.bytes`.
3. Compare side by side: header/logo, item wrapping (long names, modifiers), right-aligned amounts, dashed rules, the
   pay-link QR (scan it), card-logo strip, tip-suggestion boxes, signature lines, footer; paper cut at the end.
4. Check the full width is used and nothing is clipped (58 mm printers with a 48 mm print zone vs 432-dot raster).
5. Print an EOD report and item report on both widths (383 / 631-dot rasters).
6. Text receipt (`textReceipt: true`): check code page for `£/€/₹` and column alignment at 32 / 46 chars.
7. Repeat on the Windows XP / Java 8 box — fallback glyphs and font scaler depend on the JRE.
