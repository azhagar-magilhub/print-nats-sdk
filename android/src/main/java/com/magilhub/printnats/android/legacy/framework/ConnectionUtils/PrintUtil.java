// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.ConnectionUtils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.util.Log;

import androidx.room.FtsOptions;

import com.magilhub.printnats.android.legacy.escpos.EscPosPrinter;
import com.magilhub.printnats.android.legacy.escpos.EscPosPrinterCommands;
import com.magilhub.printnats.android.legacy.escpos.connection.DeviceConnection;
import com.magilhub.printnats.android.legacy.escpos.exceptions.EscPosConnectionException;
import com.magilhub.printnats.android.legacy.escpos.exceptions.EscPosEncodingException;
import com.magilhub.printnats.android.legacy.escpos.textparser.PrinterTextParserImg;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.magilhub.printnats.android.legacy.framework.async.AsyncEscPosPrinter;
import com.magilhub.printnats.android.legacy.framework.db.entity.PrintEntity;
import com.magilhub.printnats.android.legacy.framework.models.BusinessDetails;
import com.magilhub.printnats.android.legacy.framework.models.EODRow;
import com.magilhub.printnats.android.legacy.framework.models.EodReport;
import com.magilhub.printnats.android.legacy.framework.models.Item;
import com.magilhub.printnats.android.legacy.framework.models.ItemReport;
import com.magilhub.printnats.android.legacy.framework.models.Option;
import com.magilhub.printnats.android.legacy.framework.models.OrderPaymentDetail;
import com.magilhub.printnats.android.legacy.framework.models.Orders;
import com.magilhub.printnats.android.legacy.framework.models.PaymentDetails;
import com.magilhub.printnats.android.legacy.framework.models.Receipt;
import com.magilhub.printnats.android.legacy.framework.models.ReportItems;
import com.magilhub.printnats.android.legacy.framework.models.Request;
import com.magilhub.printnats.android.legacy.framework.models.Response;
import com.magilhub.printnats.android.legacy.framework.models.TipOption;
import com.magilhub.printnats.android.legacy.framework.models.TitleValue;
import com.magilhub.printnats.android.legacy.framework.models.Total;
import com.magilhub.printnats.android.legacy.framework.models.Transaction;
import com.magilhub.printnats.android.legacy.framework.pojo.ReceiptPojo.ReceiptPojo;
import com.magilhub.printnats.android.legacy.framework.utility.Table;
import com.magilhub.printnats.android.legacy.framework.utility.Utils;
import com.magilhub.printnats.android.legacy.framework.utility.drawreceipt.ReceiptBuilder;


import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;



public class PrintUtil {

    private static final String TAG = "PrintUtil";

    static Context mContext;
    static Receipt mReceipt;
    static Bitmap logo;
    static Boolean mis58mm, mIsAuthorized;


    public static AsyncEscPosPrinter getAsyncEscPosPrinter(Context context, DeviceConnection printerConnection, Receipt receipt, Bitmap logo, boolean is58mm, boolean isAuthorized,boolean isTextReceiptPrint) {
        mContext = context;
        mReceipt = receipt;
        mis58mm = is58mm;
        DeviceConnection mPrinterConnection = printerConnection;
        mIsAuthorized = isAuthorized;


        SimpleDateFormat format = new SimpleDateFormat("'on' yyyy-MM-dd 'at' HH:mm:ss");

        AsyncEscPosPrinter printer;

        if(is58mm){
            printer = new AsyncEscPosPrinter(printerConnection, 203, 58f, 32);
        }else{
            printer = new AsyncEscPosPrinter(printerConnection, 203, 79f, 46);
        }
        printReceipt(logo, mReceipt, mPrinterConnection, mis58mm, mIsAuthorized);
        // if (isTextReceiptPrint){
        //     printImageReceipt(logo, mReceipt, mPrinterConnection, mis58mm, mIsAuthorized);
        // }else{
        //     printReceipt(logo, mReceipt, mPrinterConnection, mis58mm, mIsAuthorized);
        // }
        //printEODReport(printer,mPrinterConnection,mis58mm);

        return printer;
    }


    public static AsyncEscPosPrinter getAsyncEODPrinter(Context context, DeviceConnection printerConnection,EodReport eodReport, List<ItemReport> itemReports, boolean is58mm) {
        mContext = context;
        mis58mm = is58mm;
        DeviceConnection mPrinterConnection = printerConnection;


        SimpleDateFormat format = new SimpleDateFormat("'on' yyyy-MM-dd 'at' HH:mm:ss");
        AsyncEscPosPrinter printer;

        if(is58mm){
            printer = new AsyncEscPosPrinter(printerConnection, 203, 58f, 32);
        }else{
            printer = new AsyncEscPosPrinter(printerConnection, 203, 79f, 46);
        }


        //=printReceipt(logo, mReceipt, mPrinterConnection, mis58mm, mIsAuthorized);
          if (itemReports != null) {
            printEODItemReport(mPrinterConnection, mis58mm, itemReports);
        } else {
        printEODReport(printer, mPrinterConnection, mis58mm, eodReport);
        }
        return printer;
    }


    private static Bitmap generateQr(String link) {
        Bitmap bmp = null;
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(link, BarcodeFormat.QR_CODE, 300, 300);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

        } catch (WriterException e) {
            e.printStackTrace();
        }

        return bmp;
    }

    private static int findBreakIndex(String text, int maxLength) {
        // If text is shorter than maxLength, return the full length
        if (text.length() <= maxLength) {
            return text.length();
        }
        
        // Find the last space within the maxLength boundary
        int breakIndex = maxLength;
        while (breakIndex > 0 && text.charAt(breakIndex) != ' ') {
            breakIndex--;
        }
        
        // If no space found or the word is longer than maxLength, break at maxLength
        if (breakIndex == 0 || text.substring(0, breakIndex).contains(" ")) {
            breakIndex = maxLength;
        }
        
        return breakIndex;
    }

    private static boolean isLoyaltyRedeemedTotalCode(String code) {
        if (code == null) {
            return false;
        }
        try {
            return Double.compare(Double.parseDouble(code.trim()), 9.0d) == 0;
        } catch (Exception exception) {
            return "9".equals(code.trim());
        }
    }

    private static String formatLoyaltyRedeemedTotalValue(String value, String currencySymbol) {
        double amount = 0.0d;
        if (value != null && !value.trim().isEmpty()) {
            amount = Math.abs(Double.parseDouble(value.trim()));
        }
        return "(" + currencySymbol + String.format("%.2f", amount) + ")";
    }

    private static class LoyaltyPointReceiptRenderData {
        private String programName;
        private boolean paused;
        private String memberValue;
        private String bonusLabel;
        private String bonusValue;
        private String pointsEarnedValue;
        private String balanceValue;
        // Merchant-configurable label for "points" (from loyalty_point_receipt
        // .points_name). Defaults keep the original wording when unset.
        private String pointsLabel = "Points";
        private String pointsLabelLower = "points";
        private final List<String> promotionLines = new ArrayList<>();
        private final List<String> pausedLines = new ArrayList<>();

        private boolean hasContent() {
            return (programName != null && !programName.trim().isEmpty())
                    || (memberValue != null && !memberValue.trim().isEmpty())
                    || (bonusValue != null && !bonusValue.trim().isEmpty())
                    || (pointsEarnedValue != null && !pointsEarnedValue.trim().isEmpty())
                    || (balanceValue != null && !balanceValue.trim().isEmpty())
                    || !promotionLines.isEmpty()
                    || !pausedLines.isEmpty();
        }
    }

    private static String formatMultiplierValue(Double multiplier) {
        if (multiplier == null) {
            return null;
        }

        String formatted = String.format(Locale.US, "%.2f", multiplier);
        formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        return formatted;
    }

    /**
     * Loyalty point values are sent as fractional numbers (e.g. 6767.08), so
     * they are parsed as Double. Render whole numbers without a trailing ".0"
     * and fractional values with up to 2 decimals (trailing zeros stripped).
     */
    private static String formatLoyaltyPoints(Double value) {
        if (value == null) {
            return null;
        }
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.longValue());
        }
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return bd.toPlainString();
    }

    /**
     * Resolve the merchant points label sent on the receipt (points_name).
     * Falls back to the supplied default ("Points"/"points") when unset so the
     * receipt keeps its original wording.
     */
    private static String resolvePointsLabel(String pointsName, String fallback) {
        return (pointsName != null && !pointsName.trim().isEmpty()) ? pointsName.trim() : fallback;
    }

    private static String formatPointsEarnedValue(Double totalPoints, Double multiplier, boolean hasPromotionsApplied, String pointsLabel) {
        if (totalPoints == null) {
            return null;
        }

        String value = "+" + formatLoyaltyPoints(totalPoints) + " " + pointsLabel;
        String formattedMultiplier = formatMultiplierValue(multiplier);
        if (!hasPromotionsApplied && formattedMultiplier != null && !formattedMultiplier.isEmpty()) {
            value += " (" + formattedMultiplier + "x)";
        }
        return value;
    }

    private static String formatPromotionAppliedLine(Double multiplierValue, String promotionName) {
        if (promotionName == null || promotionName.trim().isEmpty()) {
            return null;
        }

        String formattedMultiplier = formatMultiplierValue(multiplierValue);
        if (formattedMultiplier != null && !formattedMultiplier.isEmpty()) {
            return formattedMultiplier + "x earning applied - " + promotionName.trim();
        }

        return promotionName.trim();
    }

    private static String formatTwoColumnLine(String left, String right, int maxCols) {
        String safeLeft = left == null ? "" : left.trim();
        String safeRight = right == null ? "" : right.trim();
        if (safeRight.isEmpty()) {
            return safeLeft;
        }

        int spaces = maxCols - (safeLeft.length() + safeRight.length());
        if (spaces < 2) {
            spaces = 2;
        }
        return safeLeft + String.valueOf(new char[spaces]).replace("\0", " ") + safeRight;
    }

    /**
     * Draws a loyalty row with a fixed two-column layout (modelled on the
     * receipt's items table): the left column holds the label, the right
     * column holds the value. Each side wraps independently within its own
     * column width, so a long value (e.g., "earn 3.5x rewards on this
     * kohinoor") stacks vertically on the right while the label stays on a
     * single line on the left. The row's height is the taller of the two
     * wrapped sides. Returns the new currentY for the next row.
     */
    private static int drawLoyaltyTwoColumnRow(android.graphics.Canvas canvas,
                                               android.graphics.Paint paint,
                                               String left,
                                               String right,
                                               float leftX,
                                               float leftColWidth,
                                               float rightX,
                                               float rightColWidth,
                                               int currentY,
                                               int textSize,
                                               float lineHeight) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;

        List<String> leftLines = wrapText(safeLeft, paint, (int) leftColWidth);
        List<String> rightLines = wrapText(safeRight, paint, (int) rightColWidth);
        int rowLineCount = Math.max(leftLines.size(), rightLines.size());

        for (int i = 0; i < rowLineCount; i++) {
            float baselineY = currentY + textSize;
            if (i < leftLines.size()) {
                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                canvas.drawText(leftLines.get(i), leftX, baselineY, paint);
            }
            if (i < rightLines.size()) {
                paint.setTextAlign(android.graphics.Paint.Align.RIGHT);
                canvas.drawText(rightLines.get(i), rightX, baselineY, paint);
            }
            currentY += (int) lineHeight;
        }

        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
        return currentY;
    }

    private static LoyaltyPointReceiptRenderData getLoyaltyPointReceiptRenderData(Receipt.LoyaltyPointReceipt loyaltyPointReceipt) {
        LoyaltyPointReceiptRenderData data = new LoyaltyPointReceiptRenderData();
        if (loyaltyPointReceipt == null) {
            return data;
        }

        Receipt.LoyaltyPointReceiptProgram program = loyaltyPointReceipt.getProgram();
        data.programName = program != null ? program.getName() : null;
        data.pointsLabel = resolvePointsLabel(loyaltyPointReceipt.getPointsName(), "Points");
        data.pointsLabelLower = resolvePointsLabel(loyaltyPointReceipt.getPointsName(), "points");
        data.paused = program != null && Boolean.TRUE.equals(program.getIsPaused());

        if (data.paused) {
            String displayProgramName = (data.programName != null && !data.programName.trim().isEmpty())
                    ? data.programName.trim()
                    : "Rewards";
            data.pausedLines.add(displayProgramName + " is currently paused.");
            data.pausedLines.add("Don't worry - your account is safe,");
            data.pausedLines.add("but no " + data.pointsLabelLower + " will be added for this visit.");
            return data;
        }

        Receipt.LoyaltyPointReceiptTierAtOrder tierAtOrder = loyaltyPointReceipt.getTierAtOrder();
        if (tierAtOrder != null) {
            String tierName = tierAtOrder.getTierName();
            String tierDescription = tierAtOrder.getDescription();
            if (tierName != null && !tierName.trim().isEmpty()) {
                data.memberValue = tierName.trim();
            }
            if (tierName != null && !tierName.trim().isEmpty() && tierDescription != null && !tierDescription.trim().isEmpty()) {
                data.bonusLabel = tierName.trim() + " bonus";
                data.bonusValue = tierDescription.trim();
            }
        }

        boolean hasPromotionsApplied = loyaltyPointReceipt.getPromotionsApplied() != null
                && !loyaltyPointReceipt.getPromotionsApplied().isEmpty();

        Receipt.LoyaltyPointReceiptPointsEarned pointsEarned = loyaltyPointReceipt.getPointsEarned();
        if (pointsEarned != null) {
            data.pointsEarnedValue = formatPointsEarnedValue(
                    pointsEarned.getTotalPoints(),
                    tierAtOrder != null ? tierAtOrder.getMultiplier() : null,
                    hasPromotionsApplied,
                    data.pointsLabel
            );
        }

        if (loyaltyPointReceipt.getPromotionsApplied() != null) {
            for (Receipt.LoyaltyPointReceiptPromotion promotion : loyaltyPointReceipt.getPromotionsApplied()) {
                if (promotion == null) continue;
                String line = formatPromotionAppliedLine(promotion.getMultiplierValue(), promotion.getName());
                if (line != null && !line.isEmpty()) {
                    data.promotionLines.add(line);
                }
            }
        }

        if (loyaltyPointReceipt.getBalanceAfter() != null) {
            data.balanceValue = formatLoyaltyPoints(loyaltyPointReceipt.getBalanceAfter()) + " " + data.pointsLabel;
        }

        return data;
    }

    private static LoyaltyPointReceiptRenderData getLoyaltyPointReceiptRenderData(ReceiptPojo.LoyaltyPointReceipt loyaltyPointReceipt) {
        LoyaltyPointReceiptRenderData data = new LoyaltyPointReceiptRenderData();
        if (loyaltyPointReceipt == null) {
            return data;
        }

        ReceiptPojo.LoyaltyPointReceiptProgram program = loyaltyPointReceipt.getProgram();
        data.programName = program != null ? program.getName() : null;
        data.pointsLabel = resolvePointsLabel(loyaltyPointReceipt.getPointsName(), "Points");
        data.pointsLabelLower = resolvePointsLabel(loyaltyPointReceipt.getPointsName(), "points");
        data.paused = program != null && Boolean.TRUE.equals(program.getIsPaused());

        if (data.paused) {
            String displayProgramName = (data.programName != null && !data.programName.trim().isEmpty())
                    ? data.programName.trim()
                    : "Rewards";
            data.pausedLines.add(displayProgramName + " is currently paused.");
            data.pausedLines.add("Don't worry - your account is safe,");
            data.pausedLines.add("but no " + data.pointsLabelLower + " will be added for this visit.");
            return data;
        }

        ReceiptPojo.LoyaltyPointReceiptTierAtOrder tierAtOrder = loyaltyPointReceipt.getTierAtOrder();
        if (tierAtOrder != null) {
            String tierName = tierAtOrder.getTierName();
            String tierDescription = tierAtOrder.getDescription();
            if (tierName != null && !tierName.trim().isEmpty()) {
                data.memberValue = tierName.trim();
            }
            if (tierName != null && !tierName.trim().isEmpty() && tierDescription != null && !tierDescription.trim().isEmpty()) {
                data.bonusLabel = tierName.trim() + " bonus";
                data.bonusValue = tierDescription.trim();
            }
        }

        boolean hasPromotionsApplied = loyaltyPointReceipt.getPromotionsApplied() != null
                && !loyaltyPointReceipt.getPromotionsApplied().isEmpty();

        ReceiptPojo.LoyaltyPointReceiptPointsEarned pointsEarned = loyaltyPointReceipt.getPointsEarned();
        if (pointsEarned != null) {
            data.pointsEarnedValue = formatPointsEarnedValue(
                    pointsEarned.getTotalPoints(),
                    tierAtOrder != null ? tierAtOrder.getMultiplier() : null,
                    hasPromotionsApplied,
                    data.pointsLabel
            );
        }

        if (loyaltyPointReceipt.getPromotionsApplied() != null) {
            for (ReceiptPojo.LoyaltyPointReceiptPromotion promotion : loyaltyPointReceipt.getPromotionsApplied()) {
                if (promotion == null) continue;
                String line = formatPromotionAppliedLine(promotion.getMultiplierValue(), promotion.getName());
                if (line != null && !line.isEmpty()) {
                    data.promotionLines.add(line);
                }
            }
        }

        if (loyaltyPointReceipt.getBalanceAfter() != null) {
            data.balanceValue = formatLoyaltyPoints(loyaltyPointReceipt.getBalanceAfter()) + " " + data.pointsLabel;
        }

        return data;
    }
    /**
     * True when a transaction status code means REFUND rather than payment.
     *
     * 32 = P.Refund, 26 = Refund. Same pair the IMAGE renderer already tests
     * inline at the "Refund:" / "Refund by " sites (:4349, :4468, :4563).
     *
     * WHY THIS EXISTS (user repro 2026-09-15, "print the receipt via refund
     * popup still displays Paid by instead of Refund by"): the ESC/POS TEXT
     * renderer had no such check anywhere, so a refund slip printed
     * "Refunded Items" and "Refunded Amount" and then ended in
     * "Paid by OFFLINE_QR 22.60" -- the receipt contradicting itself. The
     * image path got the check; the text path never did. Null-safe, because
     * the existing inline `.getStatusCode().toString()` calls are not.
     */
    private static boolean isRefundStatusCode(String statusCode) {
        return "32".equals(statusCode) || "26".equals(statusCode);
    }

    /** `statusCode` of transactions[0], or null when there is no usable row. */
    private static String firstTxnStatusCode(java.util.List<com.magilhub.printnats.android.legacy.framework.models.Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) return null;
        com.magilhub.printnats.android.legacy.framework.models.Transaction t = transactions.get(0);
        return t == null ? null : t.getStatusCode();
    }

    /**
     * Helper to add a cancelled/refund item row to the text-based receipt table.
     * Extracted to avoid duplicating item rendering logic for voidedItems, refundItems, and refundedItems.
     */
    private static void addItemRowToTable(Table table, Item item, Receipt receipt, int maxCharLen, String itemNameFormatter, String secondLineItemFormatted) {
        Float customizationValue = 0.0F;
        if (item.getOptions() != null && item.getOptions().size() > 0) {
            for (Option op : item.getOptions()) {
                customizationValue += Float.parseFloat(op.getPrice());
            }
        }
        customizationValue = item.getIsWeightBased() ? 0 : Integer.parseInt(item.getQuantity()) * customizationValue;

        Float subTotal = Float.parseFloat(item.getSubTotal());
        Float itemPrice = subTotal - customizationValue;
        // If subTotal is 0 but item has a unit price (e.g. full order cancellation), use price × qty
        if (itemPrice == 0.0 && item.getPrice() != null && item.getPrice() > 0) {
            itemPrice = item.getIsWeightBased() ? Float.parseFloat(item.getQuantity()) * item.getPrice().floatValue()
                    : Integer.parseInt(item.getQuantity()) * item.getPrice().floatValue();
        }
        if (itemPrice == 0.0) {
            String itemName = item.getItemName() != null ? item.getItemName() : "";
            if (item.getIsWeightBased()) {
                itemName = itemName + "\n(" + item.getPrice() + "/" + item.getPriceUnit() + ")";
            }
            String rowR = itemName + ";" + " " + ";" + " ";
            table.addRow(rowR);
        } else {
            String formattedPrice = String.format("%5.2f", itemPrice);
            String formattedItemName = item.getItemName() != null ? item.getItemName() : "";
            if (item.getIsWeightBased()) {
                formattedItemName = formattedItemName + "\n(" + item.getPrice() + "/" + item.getPriceUnit() + ")";
            }
            boolean firstLine = true;
            while (formattedItemName.length() > maxCharLen) {
                int breakIndex = findBreakIndex(formattedItemName, maxCharLen);
                String line = formattedItemName.substring(0, breakIndex);
                formattedItemName = formattedItemName.substring(breakIndex).trim();
                if (firstLine) {
                    String iQty = item.getIsWeightBased() ? item.getQuantity() : String.format("%4s", item.getQuantity());
                    table.addRow(String.format(itemNameFormatter, line, iQty, formattedPrice));
                    firstLine = false;
                } else {
                    table.addRow(String.format(secondLineItemFormatted, line));
                }
            }
            if (!formattedItemName.isEmpty()) {
                String iQty = item.getIsWeightBased() ? item.getQuantity() : String.format("%4s", item.getQuantity());
                if (firstLine) {
                    table.addRow(String.format(itemNameFormatter, formattedItemName, iQty, formattedPrice));
                } else {
                    table.addRow(String.format(secondLineItemFormatted, formattedItemName));
                }
            }
        }
        if (item.getOptions() != null && item.getOptions().size() > 0) {
            for (Option op : item.getOptions()) {
                String customizationPrice = String.format("%5.2f", Integer.parseInt(item.getQuantity()) * Float.parseFloat(op.getPrice()));
                String customizationCount = receipt.getIsCustomizationCountRequired() ? String.valueOf(Integer.parseInt(op.getQuantity()) * Integer.parseInt(item.getQuantity())) : " ";
                String fmtItemName = op.getOptionName();
                boolean firstLine = true;
                while (fmtItemName.length() > maxCharLen) {
                    int breakIndex = findBreakIndex(fmtItemName, maxCharLen);
                    String line = fmtItemName.substring(0, breakIndex);
                    fmtItemName = fmtItemName.substring(breakIndex).trim();
                    if (firstLine) {
                        table.addRow(String.format(itemNameFormatter, "   " + line, String.format("%4s", customizationCount), customizationPrice));
                        firstLine = false;
                    } else {
                        table.addRow(String.format("   " + secondLineItemFormatted, line));
                    }
                }
                if (!fmtItemName.isEmpty()) {
                    if (firstLine) {
                        table.addRow(String.format(itemNameFormatter, "   " + fmtItemName, String.format("%4s", customizationCount), customizationPrice));
                    } else {
                        table.addRow(String.format("   " + secondLineItemFormatted, fmtItemName));
                    }
                }
            }
        }
    }

    /**
     * Helper to draw a list of ReceiptItems on a canvas for image-based receipt printing.
     * Used for voidedItems, refundItems, and legacy refundedItems.
     * Returns the updated currentY position after drawing.
     */
    private static Double parseNumberOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatOptionQty(double qty) {
        if (!Double.isInfinite(qty) && !Double.isNaN(qty) && qty == Math.rint(qty)) {
            return String.valueOf((long) qty);
        }
        String formatted = String.format("%.4f", qty);
        return formatted.contains(".") ? formatted.replaceAll("0+$", "").replaceAll("\\.$", "") : formatted;
    }

    /**
     * Option quantity to print. v2/orders/item sends actualQuantity already multiplied across the
     * option's own count and the parent item quantity ("5" per item x 8 items -> "40.0000"); any
     * other payload omits it and we derive it the old way.
     */
    private static String resolveOptionQty(ReceiptPojo.ItemOption option, String itemQty) {
        Double actualQty = parseNumberOrNull(option.getActualQuantity());
        if (actualQty != null) return formatOptionQty(actualQty);

        return option.getQuantity() != null
                ? String.valueOf(Integer.parseInt(option.getQuantity()) * Integer.parseInt(itemQty))
                : "0";
    }

    /**
     * Option line total. actualPrice is the fully extended amount (unit price x actualQuantity), so
     * unlike the fallback it also accounts for options ordered more than once per item.
     */
    private static double resolveOptionTotal(ReceiptPojo.ItemOption option, String itemQty) {
        if (option.getActualPrice() != null) return option.getActualPrice();

        Double unitPrice = parseNumberOrNull(option.getPrice());
        return unitPrice != null ? unitPrice * Integer.parseInt(itemQty) : 0.0;
    }

    /** Whether the option carries a charge worth printing qty/total columns for. */
    private static boolean isOptionPriced(ReceiptPojo.ItemOption option) {
        if (option.getActualPrice() != null) return option.getActualPrice() > 0;

        Double unitPrice = parseNumberOrNull(option.getPrice());
        return unitPrice != null && unitPrice > 0;
    }

    private static float drawReceiptItemsOnCanvas(
            List<ReceiptPojo.ReceiptItem> items, Canvas canvas, Paint paint, float currentY,
            float itemX, float qtyX, float qtyColWidth, float totalX, float totalColWidth, float itemColWidth,
            float tableHeaderValueSize, float tableRowSize, String currencySymbol, boolean isCustomizationCountRequired) {

        for (ReceiptPojo.ReceiptItem item : items) {
            if (item == null) continue;

            String itemName = item.getItemName() != null ? item.getItemName() : "";
            if (item.getIsWeightBased()) {
                itemName = itemName + "\n(" + currencySymbol + " " + item.getPrice() + "/" + item.getPriceUnit() + ")";
            }
            String qty = item.getQuantity() != null ? item.getQuantity() : "0";
            String total = item.getSubTotal() != null ?
                    String.format("%.2f", Double.parseDouble(item.getSubTotal())) : "0.00";
            String itemPrice = String.valueOf(item.getPrice()) != null ?
                    String.format("%.2f", Double.parseDouble(qty) * item.getPrice()) : "0.00";

            List<String> wrappedLines = wrapText(itemName, paint, itemColWidth);

            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(wrappedLines.get(0), itemX, currentY + tableHeaderValueSize, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(qty, qtyX + (qtyColWidth / 2), currentY + tableHeaderValueSize, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(isCustomizationCountRequired ? currencySymbol + itemPrice : currencySymbol + total, totalX + totalColWidth, currentY + tableHeaderValueSize, paint);

            currentY += tableRowSize;

            for (int j = 1; j < wrappedLines.size(); j++) {
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(wrappedLines.get(j), itemX, currentY + tableHeaderValueSize, paint);
                currentY += tableRowSize;
            }

            List<ReceiptPojo.ItemOption> options = item.getOptions();
            if (options != null) {
                for (ReceiptPojo.ItemOption option : options) {
                    if (option == null) continue;

                    String optionName = "   " + (option.getOptionName() != null ? option.getOptionName() : "");
                    String optionQty = resolveOptionQty(option, qty);

                    if (isOptionPriced(option) && isCustomizationCountRequired) {
                        String optionTotal = String.format("%.2f", resolveOptionTotal(option, qty));
                        paint.setTextAlign(Paint.Align.LEFT);
                        canvas.drawText(optionName, itemX, currentY + tableHeaderValueSize, paint);

                        paint.setTextAlign(Paint.Align.RIGHT);
                        canvas.drawText(optionQty, qtyX + (qtyColWidth / 2), currentY + tableHeaderValueSize, paint);

                        paint.setTextAlign(Paint.Align.RIGHT);
                        canvas.drawText(optionTotal, totalX + totalColWidth, currentY + tableHeaderValueSize, paint);
                    } else {
                        paint.setTextAlign(Paint.Align.LEFT);
                        canvas.drawText(optionName, itemX, currentY + tableHeaderValueSize, paint);
                    }
                    currentY += tableRowSize;
                }
            }
        }
        return currentY;
    }

    private static String centerTextPadStrict(String text, int maxCols, boolean doubleWidth) {
        if (text == null) text = "";
        text = text.replace("\t", " ").trim(); // avoid tabs; trim leading spaces that break centering

        // When double width is ON, every character (including spaces) is 2x wide,
        // so the number of printable columns is effectively halved.
        int effectiveCols = doubleWidth ? Math.max(1, maxCols / 2) : maxCols;

        // Clamp text if longer than the available columns (optional: wrap instead)
        String fit = text.length() > effectiveCols ? text.substring(0, effectiveCols) : text;

        int remaining = effectiveCols - fit.length();
        int leftPad  = remaining / 2;              // floor
        int rightPad = remaining - leftPad;        // keep total width exact

        StringBuilder sb = new StringBuilder(effectiveCols);
        for (int i = 0; i < leftPad; i++) sb.append(' ');
        sb.append(fit);
        for (int i = 0; i < rightPad; i++) sb.append(' ');
        return sb.toString();
    }



    private static void printReceipt(Bitmap logo, Receipt receipt, DeviceConnection printerConnection, boolean is58mm, boolean isAuthorized) {
       
        if (receipt.openCashDrawer() && receipt.openCashDrawer() == true) {
            EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);
            try {
                printerCommands.connect();
                printerCommands.openCashBox();
                receipt.setOpenCashDrawer(false);
                
            } catch (EscPosConnectionException e) {
                System.err.println("Error: Unable to connect to the printer. Please check if the device is available.");
                e.printStackTrace(); // Log the full error for debugging
                // Optionally, show an error message to the user
            } catch (Exception e) {
                System.err.println("Unexpected error occurred while opening the cash drawer.");
                e.printStackTrace();
            }
            return;
        }


        String column = "ITEM;QTY;TOTAL";

        Table table = new Table(column, ";", new int[]{16, 8, 8});
        int maxer = 32;
        if (!is58mm) {
            table = new Table(column, ";", new int[]{30, 10, 6});
            maxer = 46;
        }


        //    Bitmap paymentQR = generateQr(receipt.getPaymentlink());

        //   Log.e(TAG, "printReceipt: logo: "+logo.getHeight());

        try {
            String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");
            table.addRow(dashedLine);
            StringBuilder stringBuilder = new StringBuilder();

            EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);
            BusinessDetails businessDetails = receipt.getBusinessDetails();

            printerCommands.connect();

            printerConnection.write(new byte[]{0x1D, 0x21, 0x00});
            //printerConnection.write(new byte[]{0x1D, 0x20, 2});

            byte[] fontStyle = new byte[]{0x1D, 0x21, 0x00};
            Thread.sleep(500);
            //printerCommands.reset();
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);

            Log.e(TAG, "receipt.getCashInfo() - " + receipt.getCashInfo());
            JSONObject obj = receipt.getCashInfo() != null ? new JSONObject(receipt.getCashInfo().toString()) : null;
            if (obj != null) {
                Log.e(TAG, "obj" + obj);
                String country = obj.getString("restaurantCountry");
                String currency = country.contains("US") ? "$" : "₹";
                double sales = obj.getDouble("cashSales");
                double countedAmount = obj.getDouble("countedAmount");
                double expectedAmount = obj.getDouble("expectedAmount");
                double floatAmount = obj.getDouble("floatAmount");
                double payIn = obj.getDouble("payIn");
                double payOut = obj.getDouble("payOut");
                String shiftEndTime = obj.getString("shiftEndTime");
                String outletName = obj.getString("outletName");
                String shiftStartTime = obj.getString("shiftStartTime");
                String user = obj.getString("user");
                double variance = obj.getDouble("variance");

                printerCommands.printText(outletName.split(",")[0].toString() + "\n\n", EscPosPrinterCommands.TEXT_SIZE_BIG);
                printerCommands.printText(outletName.split(",")[1].toString() + "\n\n", EscPosPrinterCommands.TEXT_SIZE_BIG);
                printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);

                printerCommands.printText("Start Time " + shiftStartTime + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("End Time   " + shiftEndTime + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("Staff      " + user + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);

                printerCommands.printText("---------------------------------------------- \n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);


                printerCommands.printText("Sales Summary" + "\n\n", EscPosPrinterCommands.TEXT_SIZE_BIG);
                printerCommands.printText("Float amount      " + currency + String.format("%.2f", floatAmount) + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("Cash Sales        " + currency + String.format("%.2f", sales) + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("PayIn             " + currency + String.format("%.2f", payIn) + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("Payout           " + "(" + currency + String.format("%.2f", payOut) + ")" + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                // printerCommands.printText("Total             $-"+ ((floatAmount+sales+payIn)-payOut) +"\n", EscPosPrinterCommands.TEXT_SIZE_BIG);


                printerCommands.printText("---------------------------------------------- \n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("Cash Reconcilitation" + "\n\n", EscPosPrinterCommands.TEXT_SIZE_BIG);
                printerCommands.printText("Expected Amount   " + currency + String.format("%.2f", expectedAmount) + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("Counted Amount    " + currency + String.format("%.2f", countedAmount) + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.printText("Variance          " + currency + String.format("%.2f", variance) + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                //  printerCommands.printText("Total             $"+ (countedAmount - expectedAmount) +"\n", EscPosPrinterCommands.TEXT_SIZE_BIG);

                printerCommands.printText("\n\n\n\n\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.feedPaper(3);
                printerCommands.cutPaper();
                Thread.sleep(1000);
                return;
            }


            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);

            int cols = is58mm ? 32 : 46;

            String businessName = businessDetails.getName();
            String locationName = businessDetails.getCurrentLocation();
            String kotNoValue = receipt.getKotNo();
            String showKotValue = receipt.getShowKotNumber();
            String orderNoValue = receipt.getOrderNo();
            String showReceiptValue = receipt.getShowReceiptNo();

            String nameLine = centerTextPadStrict(businessName, cols, true);
            printerCommands.printText(nameLine, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
            String locationLine = centerTextPadStrict(locationName, cols, false);
            printerCommands.printText("\n" + locationLine, new byte[]{0x1D, 0x21, 0x00});

            if (kotNoValue != null && !kotNoValue.trim().isEmpty() && showKotValue != null && !showKotValue.toString().isEmpty() && "true".equalsIgnoreCase(showKotValue.toString())) {
                String kotNoLine = centerTextPadStrict("Ticket #" + kotNoValue, cols, true);
                printerCommands.printText("\n" + kotNoLine, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
            }

            if (orderNoValue != null && !orderNoValue.trim().isEmpty() && showReceiptValue != null && !showReceiptValue.toString().isEmpty() && "true".equalsIgnoreCase(showReceiptValue.toString())) {
                String orderNoLine = centerTextPadStrict("#" + orderNoValue, cols, true);
                printerCommands.printText("\n" + orderNoLine, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
            }

            printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);

            stringBuilder = new StringBuilder();

            if (receipt.printQR()) {
                printerCommands.printText("Contact number " + receipt.getBusinessDetails().getContactNumber() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                        EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_WEIGHT_BOLD);//Contact Number
                stringBuilder = new StringBuilder();
                stringBuilder.append(Utils.wrap(businessDetails.getAddress(), is58mm, Utils.DOUBLE_WIDTH, 0));
                stringBuilder.append("\n\n");
                printerCommands.printText(stringBuilder.toString(), EscPosPrinterCommands.TEXT_SIZE_NORMAL);// business address
                if (receipt.getFullName() != null) {
                    printerCommands.printText("Primary customer - " + receipt.getFullName() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                            EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
                }
                Bitmap customerIdQR = generateQr(receipt.getCustomerId());
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(customerIdQR));
                Thread.sleep(1000);
                printerCommands.printText("\n\n\n\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                printerCommands.feedPaper(4);
                printerCommands.cutPaper();
                Log.e(TAG, "printReceipt: print ended ");
                return;
            }
            String itemNameFormatter = "%-29s %-4s %10s";
            String secondLineItemFormatted = "%-29s";
            int maxCharLen = 20;
            if (is58mm) {
                itemNameFormatter = "%-12s %-4s %10s";
                secondLineItemFormatted = "%-12s";
                maxCharLen = 12;
            }
            for (Item item : receipt.getItems()) {
                Float customizationValue = 0.0F;
                if (item.getOptions() != null && item.getOptions().size() > 0) {
                    for (Option op : item.getOptions()) {
                        customizationValue += Float.parseFloat(op.getPrice());
                    }
                }
                customizationValue = item.getIsWeightBased() ? 0 : Integer.parseInt(item.getQuantity()) * customizationValue;
                Float subTotal = Float.parseFloat(item.getSubTotal());
                Float itemPrice = subTotal - customizationValue;
                boolean isFreeItem = false;

                // If subTotal is 0 but item has a unit price (e.g. full order cancellation), use price × qty
                if (itemPrice == 0.0 && item.getPrice() != null && item.getPrice() > 0) {
                    itemPrice = item.getIsWeightBased() ? Float.parseFloat(item.getQuantity()) * item.getPrice().floatValue()
                            : Integer.parseInt(item.getQuantity()) * item.getPrice().floatValue();
                }
                if (itemPrice == 0.0 && !isFreeItem) {

                    String itemName = item.getItemName() != null ? item.getItemName() : "";
                    if (item.getIsWeightBased()) {
                        itemName = itemName + "\n(" + item.getPrice() + "/" + item.getPriceUnit() + ")";
                    }
                    Log.e("itemName", itemName);
                    String row = itemName + ";" + " " + ";" + " ";
                    table.addRow(row);
                } else {
                    float displayItemPrice = isFreeItem ? 0.0F : itemPrice;
                    String formattedPrice = String.format("%5.2f", displayItemPrice);
                    String formattedItemName = item.getItemName() != null ? item.getItemName() : "";
                    // Append "(Free)" to the item name when this row represents
                    // a redeemed loyalty free_item reward, so the customer can
                    // tell at a glance that the $0.00 price is intentional.
                    if (isFreeItem) {
                        formattedItemName = formattedItemName + " (Free)";
                    }
                    if (item.getIsWeightBased()) {
                        formattedItemName = formattedItemName + "\n(" + item.getPrice() + "/" + item.getPriceUnit() + ")";
                    }
                    Log.e("itemName", formattedPrice);
                    boolean firstLine = true;
                    while (formattedItemName.length() > maxCharLen) {
                        int breakIndex = findBreakIndex(formattedItemName, maxCharLen);
                        String line = formattedItemName.substring(0, breakIndex);
                        formattedItemName = formattedItemName.substring(breakIndex).trim();
                        if (firstLine) {
                            // Add the first line with quantity and price
                            String iQty = item.getIsWeightBased() ? item.getQuantity() : String.format("%4s", item.getQuantity());
                            table.addRow(String.format(itemNameFormatter, line, iQty, formattedPrice));
                            firstLine = false;
                        } else {
                            table.addRow(String.format(secondLineItemFormatted, line));
                        }
                    }
                    if (!formattedItemName.isEmpty()) {
                        if (firstLine) {
                            String iQty = item.getIsWeightBased() ? item.getQuantity() : String.format("%4s", item.getQuantity());
                            table.addRow(String.format(itemNameFormatter, formattedItemName, iQty, formattedPrice));
                        } else {
                            table.addRow(String.format(secondLineItemFormatted, formattedItemName));
                        }
                    }
                }
                if (item.getOptions() != null && item.getOptions().size() > 0) {
                    for (Option op : item.getOptions()) {
                        String customizationPrice = String.format("%5.2f", Integer.parseInt(item.getQuantity()) * Float.parseFloat(op.getPrice()));
                        String customizationCount = receipt.getIsCustomizationCountRequired() ? String.valueOf(Integer.parseInt(op.getQuantity()) * Integer.parseInt(item.getQuantity())) : " ";
                        String formattedItemName = op.getOptionName();
                        boolean firstLine = true;
                        while (formattedItemName.length() > maxCharLen) {
                            int breakIndex = findBreakIndex(formattedItemName, maxCharLen);
                            String line = formattedItemName.substring(0, breakIndex);
                            formattedItemName = formattedItemName.substring(breakIndex).trim();
                            if (firstLine) {
                                table.addRow(String.format(itemNameFormatter, "   " + line, String.format("%4s", customizationCount), customizationPrice));
                                firstLine = false;
                            } else {
                                table.addRow(String.format("   " + secondLineItemFormatted, line));
                            }
                        }
                        if (!formattedItemName.isEmpty()) {
                            if (firstLine) {
                                table.addRow(String.format(itemNameFormatter, "   " + formattedItemName, String.format("%4s", customizationCount), customizationPrice));
                            } else {
                                table.addRow(String.format("   " + secondLineItemFormatted, formattedItemName));
                            }
                        }
                    }
                }
            }


            // Refund Items (Cancelled after payment)
            List<Item> refundItems = receipt.getRefundItems();
            if (refundItems != null && !refundItems.isEmpty()) {
                String row = "\nRefunded Items";
                table.addRow(row);
                for (Item item : refundItems) {
                    addItemRowToTable(table, item, receipt, maxCharLen, itemNameFormatter, secondLineItemFormatted);
                }
            }

            // Voided Items (Cancelled before payment)
            List<Item> voidedItems = receipt.getVoidedItems();
            if (voidedItems != null && !voidedItems.isEmpty()) {
                String row = "\nCancelled Items";
                table.addRow(row);
                for (Item item : voidedItems) {
                    addItemRowToTable(table, item, receipt, maxCharLen, itemNameFormatter, secondLineItemFormatted);
                }
            }

            // Legacy: refundedItems (backward compat — only if new arrays are empty)
            List<Item> refundedItems = receipt.getRefundedItems();
            if (refundedItems != null && !refundedItems.isEmpty()
                    && (voidedItems == null || voidedItems.isEmpty())
                    && (refundItems == null || refundItems.isEmpty())) {
                String row = "\nCancelled Items";
                table.addRow(row);
                for (Item item : refundedItems) {
                    addItemRowToTable(table, item, receipt, maxCharLen, itemNameFormatter, secondLineItemFormatted);
                }
            }

            table.addRow(dashedLine);
        
        String tableText = table.getTableText();


//        Bitmap icon = BitmapFactory.decodeResource(context.getResources(),
//                R.drawable.smalllogo);


        int targetWidth = 150;
        Bitmap rescaledBitmap = null;
        if (logo != null) {
            rescaledBitmap = Bitmap.createScaledBitmap(
                    logo,
                    targetWidth,
                    Math.round(((float) logo.getHeight()) * ((float) targetWidth) / ((float) logo.getWidth())),
                    true
            );
        }

            // if (rescaledBitmap != null)
            //     printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(rescaledBitmap)); //logo
            if (businessDetails.getCaption() != null && !businessDetails.getCaption().isEmpty()) {
                String[] caption = businessDetails.getCaption().split(",");
                 if(caption[0]!=null && !caption[0].isEmpty()) {
                printerCommands.printText("GSTIN " +caption[0] + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                        EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_WEIGHT_BOLD);//GST
                }
                if(caption[1]!=null && !caption[1].isEmpty()) {
                    printerCommands.printText("FSSAI " + caption[1] + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                            EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_WEIGHT_BOLD);//FSSAI
                }
                if(receipt.getBusinessDetails().getContactNumber() !=null &&
                        !receipt.getBusinessDetails().getContactNumber().isEmpty()) {
                    printerCommands.printText("Contact Number " + receipt.getBusinessDetails().getContactNumber() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                            EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_WEIGHT_BOLD);//Contact Number
                }
            }
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);


//            if (kotNo != null && !kotNo.trim().isEmpty() && showKotNo != null && !showKotNo.toString().isEmpty() && "true".equalsIgnoreCase(showKotNo.toString())) {
//                printerCommands.printText("#"+kotNo, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
//            }

//            String showReceiptNo = receipt.getShowReceiptNo();
//            String orderNoVal = "";
//
//            if (receipt.getOrderNo() != null && !receipt.getOrderNo().trim().isEmpty() && showReceiptNo != null && !showReceiptNo.toString().isEmpty() && "true".equalsIgnoreCase(showReceiptNo.toString())) {
//                orderNoVal =receipt.getOrderNo() != null ?  "#" +receipt.getOrderNo(): "";
//            }

            //printerCommands.printText( receipt.getOrderSourceName() != null  && !receipt.getOrderSourceName().isEmpty()  ? orderNoVal + '-' + receipt.getOrderSourceName()+  "\n": orderNoVal + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH); //receipt number


            if(receipt.getOTP()!=null )  {
                printerCommands.printText("OTP: " + receipt.getOTP() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
            }
            //printerCommands.printText(receipt.getOrderDate() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);//order date
            String user = "";
            String time = receipt.getOrderTime();
            String timeuserLine = receipt.getOrderDate() + ' ' + time + "\n";
            printerCommands.printText(timeuserLine, fontStyle);//time
//            if(receipt.getServerStaffName()!=null){
//                printerCommands.printText("Staff - "+receipt.getServerStaffName()+"\n", fontStyle);// staff name
//            }
            if(receipt.getTableName()!=null) {
                printerCommands.printText("Table name: " + receipt.getTableName() + "\n", fontStyle);
            }
            if(receipt.getFullName()!=null || receipt.getPhone()!=null){
                printerCommands.printText( receipt.getFullName()+"   "+ receipt.getPhone()+ "\n", fontStyle);
            }
            if(receipt.getPaymentType()!=null &&!receipt.getPaymentType().isEmpty()){
                printerCommands.printText( receipt.getPaymentType()+ "\n", fontStyle);
            }
            //String starredLine = String.valueOf(new char[maxer]).replace("\0", "*");
            printerCommands.printText(dashedLine + "\n");
            if(receipt.getItems().size() > 0 || (receipt.getVoidedItems() != null && !receipt.getVoidedItems().isEmpty()) || (receipt.getRefundItems() != null && !receipt.getRefundItems().isEmpty()) || (receipt.getRefundedItems() != null && !receipt.getRefundedItems().isEmpty())) {
                printerCommands.printText(tableText); //items table
                printerCommands.printText(dashedLine + "\n"); // dashed line
            }



            String totalValue = "";
            String receiptCurrencySymbol =
                    receipt.getCountryCd() != null &&
                    receipt.getCountryCd().toUpperCase().contains("US")
                            ? "$"
                            : "₹";
            for (Total total : receipt.getTotals()) {
                if (!total.getTitle().equals("Grand Total")) {
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);
                    String value = isLoyaltyRedeemedTotalCode(total.getCode())
                            ? formatLoyaltyRedeemedTotalValue(total.getValue(), receiptCurrencySymbol)
                            : isPlainNumericValue(total.getValue())
                                ? String.format("%8.2f", Float.parseFloat(total.getValue()))
                                : (total.getValue() != null ? total.getValue().trim() : "");
                    int valueLength = value.length();
                    if (value.length() < 6) {
                        valueLength = (6 - value.length()) + valueLength;
                    }
                    // A title may span multiple lines (Discount label + offer names).
                    // Print all leading lines as-is and align the amount on the last
                    // line. Clamp padding so a long title can never produce a negative
                    // array size (which previously crashed the printer).
                    String[] titleLines = total.getTitle().split("\n");
                    for (int li = 0; li < titleLines.length - 1; li++) {
                        printerCommands.printText(titleLines[li] + "\n", fontStyle);
                    }
                    String lastTitle = titleLines[titleLines.length - 1];
                    int padCount = maxer - (lastTitle.length() + valueLength);
                    if (padCount < 0) padCount = 0;
                    String subLine = lastTitle + String.valueOf(new char[padCount]).replace("\0", " ") + value;
                    printerCommands.printText(subLine + "\n", fontStyle); //totals
                } else
                {
                    totalValue = String.format("%8.2f", Float.parseFloat(total.getValue()));
                }
            }

            String refundedAmount = receipt.getRefundedAmount();
            if(refundedAmount != null && Double.parseDouble(refundedAmount) > 0){

                printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);
                String title = "Refunded Amount";
                String value = String.format("%8.2f", Float.parseFloat(refundedAmount));
                int valueLength = value.length();
                if (value.length() < 6) {
                    valueLength = (6 - value.length()) + valueLength;
                }
                String subLine = title + String.valueOf(new char[maxer - (title.length() + valueLength)]).replace("\0", " ") + value;
                printerCommands.printText(subLine + "\n", fontStyle); //totals

            }

            printerCommands.printText(dashedLine + "\n");// dashedline
            


            String total = "TOTAL";
            String totalLine = total + String.valueOf(new char[16 - (total.length() + totalValue.length())]).replace("\0", " ") + totalValue;
            if (!is58mm) {
                totalLine = total + String.valueOf(new char[23 - (total.length() + totalValue.length())]).replace("\0", " ") + totalValue;
            }

            printerCommands.printText(totalLine + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH); //grand total

            // ---- REFUNDED FEE (activity '109') — text-receipt twin of the
            // block in generateReceiptImageFromJson. Per Figma it sits BELOW
            // the grand total between two dashed rules, heading in bold. Both
            // paths must print the same receipt; only the primitives differ.
            Receipt.RefundedFee refundedFee = receipt.getRefundedFee();
            if (refundedFee != null
                    && refundedFee.getLines() != null
                    && !refundedFee.getLines().isEmpty()) {
                printerCommands.printText(dashedLine + "\n", fontStyle);
                printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);
                // 5-arg overload: (text, size, color, reverseColor, bold).
                // NOTE the 4-arg calls elsewhere in this file pass
                // TEXT_WEIGHT_BOLD into the reverseColor slot — that is a
                // long-standing quirk, not the intended signature.
                printerCommands.printText("REFUNDED FEE\n",
                        EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                        EscPosPrinterCommands.TEXT_COLOR_BLACK,
                        null,
                        EscPosPrinterCommands.TEXT_WEIGHT_BOLD);

                for (Receipt.RefundedFeeLine feeLine : refundedFee.getLines()) {
                    if (feeLine == null) continue;
                    String feeTitle = feeLine.getName() != null ? feeLine.getName() : "";
                    String feeValue = String.format("%8.2f",
                            Float.parseFloat(feeLine.getAmount() != null ? feeLine.getAmount() : "0"));
                    int feeValueLength = feeValue.length();
                    if (feeValue.length() < 6) {
                        feeValueLength = (6 - feeValue.length()) + feeValueLength;
                    }
                    int feePad = maxer - (feeTitle.length() + feeValueLength);
                    if (feePad < 1) feePad = 1;
                    String feeSubLine = feeTitle
                            + String.valueOf(new char[feePad]).replace("\0", " ")
                            + feeValue;
                    printerCommands.printText(feeSubLine + "\n", fontStyle);
                }

                String feeTotalRaw = refundedFee.getTotalAmount();
                if (feeTotalRaw != null && !feeTotalRaw.isEmpty()) {
                    String feeTotalTitle = "Refunded Amount";
                    String feeTotalValue = String.format("%8.2f", Float.parseFloat(feeTotalRaw));
                    int feeTotalValueLength = feeTotalValue.length();
                    if (feeTotalValue.length() < 6) {
                        feeTotalValueLength = (6 - feeTotalValue.length()) + feeTotalValueLength;
                    }
                    int feeTotalPad = maxer - (feeTotalTitle.length() + feeTotalValueLength);
                    if (feeTotalPad < 1) feeTotalPad = 1;
                    String feeTotalLine = feeTotalTitle
                            + String.valueOf(new char[feeTotalPad]).replace("\0", " ")
                            + feeTotalValue;
                    printerCommands.printText(feeTotalLine + "\n", fontStyle);
                }

                printerCommands.printText(dashedLine + "\n", fontStyle);
            }

            LoyaltyPointReceiptRenderData loyaltyPointReceiptData = getLoyaltyPointReceiptRenderData(receipt.getLoyaltyPointReceipt());
            if (loyaltyPointReceiptData.hasContent()) {
                printerCommands.printText(dashedLine + "\n", fontStyle);
                if (loyaltyPointReceiptData.programName != null && !loyaltyPointReceiptData.programName.trim().isEmpty()) {
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                    printerCommands.printText(loyaltyPointReceiptData.programName.trim() + "\n", fontStyle);
                }

                if (loyaltyPointReceiptData.paused) {
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                    for (String pausedLine : loyaltyPointReceiptData.pausedLines) {
                        printerCommands.printText(pausedLine + "\n", fontStyle);
                    }
                } else {
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);
                    if (loyaltyPointReceiptData.memberValue != null && !loyaltyPointReceiptData.memberValue.isEmpty()) {
                        printerCommands.printText(formatTwoColumnLine("Member", loyaltyPointReceiptData.memberValue, maxer) + "\n", fontStyle);
                    }
                    if (loyaltyPointReceiptData.bonusValue != null && !loyaltyPointReceiptData.bonusValue.isEmpty()) {
                        printerCommands.printText(formatTwoColumnLine(loyaltyPointReceiptData.bonusLabel, loyaltyPointReceiptData.bonusValue, maxer) + "\n", fontStyle);
                    }
                    if (loyaltyPointReceiptData.pointsEarnedValue != null && !loyaltyPointReceiptData.pointsEarnedValue.isEmpty()) {
                        printerCommands.printText(formatTwoColumnLine((loyaltyPointReceiptData.pointsLabel + " earned this visit"), loyaltyPointReceiptData.pointsEarnedValue, maxer) + "\n", fontStyle);
                    }
                    for (String promotionLine : loyaltyPointReceiptData.promotionLines) {
                        printerCommands.printText(promotionLine + "\n", fontStyle);
                    }
                    if (loyaltyPointReceiptData.balanceValue != null && !loyaltyPointReceiptData.balanceValue.isEmpty()) {
                        printerCommands.printText(formatTwoColumnLine("Your balance", loyaltyPointReceiptData.balanceValue, maxer) + "\n", fontStyle);
                    }
                }
            }
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
            // Pay-QR (generatePayQr): unpaid-order pay link, straight after
            // TOTAL per the Figma. payQrLink is empty for paid/cancelled/sale
            // orders or when the flag is off. (Plain ESC/POS text can't mix
            // weights in one line — whole caption prints double-width.)
            if (receipt.getPayQrLink() != null && !receipt.getPayQrLink().isEmpty()) {
                printerCommands.printText(dashedLine + "\n");
                // UX rev 27 Jul. Pipe separators (plain ASCII) — bullet
                // U+2022 is unreliable in thermal printer codepages.
                printerCommands.printText("\nScan QR Code to Pay\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                printerCommands.printText("Fast | Secure | Contactless\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                Bitmap payQr = generateQr(receipt.getPayQrLink());
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(payQr));
                // Payment-methods strip (MHB-33608): accepted-card logos under
                // the QR. Composited to a single bitmap so it prints as one
                // image row. Null (nothing printed) when no known logo.
                Bitmap payPmStrip = buildPaymentMethodsStrip(
                        mContext, receipt.getCards(), 44, is58mm ? 384 : 560);
                if (payPmStrip != null) {
                    printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(payPmStrip));
                    if (!payPmStrip.isRecycled()) payPmStrip.recycle();
                }
                printerCommands.printText(dashedLine + "\n");
            }
            //Printing qr
            if (receipt.getPaymentlink() != null && !receipt.getPaymentlink().isEmpty() &&  receipt.getOrderSourceName() ==null ) {
                printerCommands.printText("\n"+"Scan To Pay" + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                Bitmap qr = generateQr(receipt.getPaymentlink());
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(qr));
            }
         if (receipt.getPaymentStatus()!=null && receipt.getPaymentStatus().getResponse()!=null && receipt.getPaymentStatus().getResponse().getToken()!=null ) {
//                String paidBy = "Card: ";
//                String cardNum = receipt.getPaymentStatus().getResponse().getToken();
//                String last4Digit = cardNum.substring(cardNum.length() - 4, cardNum.length());
//                String xxedLine = String.valueOf(new char[cardNum.length() - 4]).replace("\0", "X") + last4Digit;

             String CardType = (receipt.getCardType() != null && !receipt.getCardType().isEmpty()) ? receipt.getCardType()  : receipt.getPaymentStatus().getResponse().getCardType() != null ? receipt.getPaymentStatus().getResponse().getCardType() :"";
             String ExtraCardInfo = (receipt.getCardInfo() != null && !receipt.getCardInfo().isEmpty()) ? receipt.getCardInfo()  : receipt.getPaymentStatus().getResponse().getCardInfo() != null ? receipt.getPaymentStatus().getResponse().getCardInfo() :"";
             String CardInfo = ExtraCardInfo.contains(",")  ? ExtraCardInfo.substring(0, ExtraCardInfo.indexOf(",")) : ExtraCardInfo;

             // "Refund:" on a refund, matching the image path (:4349).
             String paid = (isRefundStatusCode(receipt.getPaymentStatus().getStatusCode()) ? "Refund:" : "Paid:") + receipt.getPaymentStatus().getAmountTendered() + " " +
                     (receipt.getPaymentStatus().getRequest() != null && receipt.getPaymentStatus().getRequest().getPaymentParties() != null && !receipt.getPaymentStatus().getRequest().getPaymentParties().isEmpty()
                             ? receipt.getPaymentStatus().getRequest().getPaymentParties().get(0).getPaymentCurrency() : "");
             String cardNum = receipt.getPaymentStatus().getResponse().getToken();
             String last4Digit = cardNum != null && cardNum.length() >= 4 ? cardNum.substring(cardNum.length() - 4) : "";
             String maskedCard = CardType + " " + CardInfo +  " "+ (cardNum != null && cardNum.length() >= 4 ? "XX" + last4Digit : "");
             String authResp =receipt.getPaymentStatus().getResponse().getResptext() != null ? "Auth response : " + receipt.getPaymentStatus().getResponse().getResptext():"";


             String authCode = "";
             String refId = "";

             boolean isTxnValid = receipt.getTransactions() != null &&
                     !receipt.getTransactions().isEmpty() &&
                     !receipt.getTransactionStatusCode().isEmpty() &&
                     !receipt.getTransactionStatusCode().equals("24") &&
                     !receipt.getTransactionStatusCode().equals("0");

             if (isTxnValid) {
                 String responseStr = receipt.getTransactions().get(0).getResponse();
                 if (!responseStr.isEmpty()) {
                     JSONObject json = new JSONObject(responseStr);
                     authCode = "Auth code : " + json.optString("authcode", "");

                     String refIdValue = json.optString("retref", "");
                     String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                     String maskedRef =  (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");
                     refId = "Ref Id : " + maskedRef;
                 }
             } else if (receipt.getPaymentStatus() != null && receipt.getPaymentStatus().getResponse() != null) {
                 authCode =receipt.getPaymentStatus().getResponse().getAuthcode() != null ? "Auth code : " + receipt.getPaymentStatus().getResponse().getAuthcode():"";

                 String refIdValue = receipt.getPaymentStatus().getResponse().getRetref();
                 String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                 String maskedRef =  (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");

                 refId =maskedRef; //receipt.getPaymentStatus().getResponse().getRetref() != null ?  "Ref Id : " + receipt.getPaymentStatus().getResponse().getRetref():"";
             }


             String mId =receipt.getPaymentStatus().getResponse().getMerchid() != null ? "Merchant Id : " +  "XXXXXX"+receipt.getPaymentStatus().getResponse().getMerchid().substring(receipt.getPaymentStatus().getResponse().getMerchid().length() - 4):"" ;
             String pId = receipt.getPaymentStatus().getResponse().getPayApiId() != null ? "Payment Id : " +  "XX"+receipt.getPaymentStatus().getResponse().getPayApiId().substring( receipt.getPaymentStatus().getResponse().getPayApiId().length() - 8):
                     receipt.getPaymentStatus().getResponse().getPayApi_Id() != null ? "Payment Id : " +  "XX"+receipt.getPaymentStatus().getResponse().getPayApi_Id().substring( receipt.getPaymentStatus().getResponse().getPayApi_Id().length() - 8):"";


             String[] lines = {paid, mId,maskedCard,pId, authResp, authCode, refId};
             printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
             printerCommands.printText("\nPayment Info" + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
             StringBuilder authBuilder = new StringBuilder();
             for (String line : lines) {
                 // Optionally wrap if line is long
                 if(!Objects.equals(line, "")) {
                     authBuilder.append(line+"\n");
                 }
             }
             printerCommands.printText(authBuilder.toString() + "\n", fontStyle); //Authorization Details

            }
         else if(receipt.getTransactions()!=null && !receipt.getTransactions().isEmpty()){

             Transaction transaction = receipt.getTransactions().get(0);
             Gson gson = new Gson();
             String responseStr = transaction.getResponse();
             Response paymentResponse = gson.fromJson(responseStr, Response.class);

             String reqStr = transaction.getRequest();
             Request paymentRequest = gson.fromJson(reqStr, Request.class);

             if(paymentResponse != null && !paymentResponse.toString().isEmpty() && paymentRequest != null && !paymentRequest.toString().isEmpty()){

                 String CardType = (receipt.getCardType() != null && !receipt.getCardType().isEmpty()) ? receipt.getCardType()  : paymentResponse.getCardType() != null ? paymentResponse.getCardType() :"";
                 String ExtraCardInfo =  (receipt.getCardInfo() != null && !receipt.getCardInfo().isEmpty())  ? receipt.getCardInfo()  : paymentResponse.getCardInfo() != null ? paymentResponse.getCardInfo() : "";
                 String CardInfo = ExtraCardInfo.contains(",")  ? ExtraCardInfo.substring(0, ExtraCardInfo.indexOf(",")) : ExtraCardInfo;

                 // "Refund:" on a refund, matching the image path (:4468).
                 String paid = (isRefundStatusCode(transaction.getStatusCode()) ? "Refund:" : "Paid:") + transaction.getAmountTendered() + " " +
                         (paymentRequest != null && paymentRequest.getPaymentParties() != null && !paymentRequest.getPaymentParties().isEmpty()
                                 ? paymentRequest.getPaymentParties().get(0).getPaymentCurrency() : "");
                 String tip = "Tip" + paymentResponse.getTipAmount() + " " +
                         (paymentRequest != null && paymentRequest.getPaymentParties() != null && !paymentRequest.getPaymentParties().isEmpty()
                                 ? paymentRequest.getPaymentParties().get(0).getPaymentCurrency() : "");
                 String cardNum = paymentResponse.getToken();
                 String last4Digit = cardNum != null && cardNum.length() >= 4 ? cardNum.substring(cardNum.length() - 4) : "";
                 String maskedCard = CardType + " " + CardInfo +  " "+ (cardNum != null && cardNum.length() >= 4 ? "XX" + last4Digit : "");
                 String authResp =paymentResponse.getResptext() != null ? "Auth response : " + paymentResponse.getResptext():"";


                 String authCode = "";
                 String refId = "";

                 boolean isTxnValid = receipt.getTransactions() != null &&
                         !receipt.getTransactions().isEmpty() &&
                         !receipt.getTransactionStatusCode().isEmpty() &&
                         !receipt.getTransactionStatusCode().equals("24") &&
                         !receipt.getTransactionStatusCode().equals("0");

                 if (isTxnValid) {
                     //String responseStr = receipt.getTransactions().get(0).getResponse();
                     if (!responseStr.isEmpty()) {
                         JSONObject json = new JSONObject(responseStr);
                         authCode = "Auth code : " + json.optString("authcode", "");

                         String refIdValue = json.optString("retref", "");
                         String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                         String maskedRef =  (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");
                         refId = "Ref Id : " + maskedRef;
                     }
                 } else if (receipt.getPaymentStatus() != null && paymentResponse != null) {
                     authCode =paymentResponse.getAuthcode() != null ? "Auth code : " + paymentResponse.getAuthcode():"";

                     String refIdValue = paymentResponse.getRetref();
                     String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                     String maskedRef =  (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");

                     refId =maskedRef; //receipt.getPaymentStatus().getResponse().getRetref() != null ?  "Ref Id : " + receipt.getPaymentStatus().getResponse().getRetref():"";
                 }


                 String mId =paymentResponse.getMerchid() != null ? "Merchant Id : " +  "XXXXXX"+paymentResponse.getMerchid().substring(paymentResponse.getMerchid().length() - 4):"" ;
                 String pId = paymentResponse.getPayApiId() != null ? "Payment Id : " +  "XX"+paymentResponse.getPayApiId().substring( paymentResponse.getPayApiId().length() - 8):
                         paymentResponse.getPayApi_Id() != null ? "Payment Id : " +  "XX"+paymentResponse.getPayApi_Id().substring( paymentResponse.getPayApi_Id().length() - 8):"";


                 String[] lines = {paid,tip, mId,maskedCard,pId, authResp, authCode, refId};
                 printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                 printerCommands.printText("\nPayment Info" + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                 StringBuilder authBuilder = new StringBuilder();
                 for (String line : lines) {
                     // Optionally wrap if line is long
                     if(!Objects.equals(line, "")) {
                         authBuilder.append(line+"\n");
                     }
                 }
                 printerCommands.printText(authBuilder.toString() + "\n", fontStyle); //Authorization Details

             }else{
                 printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                 // The line the bug report was about: non-card tenders
                 // (OFFLINE_QR, CASH, ...) take this fallback, which hardcoded
                 // "Paid by". Mirrors the image path (:4563).
                 String paidByKey = isRefundStatusCode(firstTxnStatusCode(receipt.getTransactions())) ? "Refund by " : "Paid by ";
                 printerCommands.printText("\n " + paidByKey + receipt.getTransactions().get(0).getTenderType() +" "+ String.format("%.2f", receipt.getTransactions().get(0).getAmountTendered())  + "\n", fontStyle);
             }
            }

            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
            //Printing qr
            if (receipt.getReviewQRLink() != null && !receipt.getReviewQRLink().isEmpty() && receipt.getReviewMessage() !=null && !receipt.getReviewMessage().isEmpty()) {
                printerCommands.printText("\n"+receipt.getReviewMessage()+ "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                Bitmap qr = generateQr(receipt.getReviewQRLink());
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(qr));
            }

            printerCommands.printText(dashedLine + "\n");

            if (receipt.getFooter() != null) {
                printerCommands.printText(  receipt.getOrderSourceName() == null ? "\n" +receipt.getFooter().getLine1()+ "\n" : "", EscPosPrinterCommands.TEXT_SIZE_NORMAL);// footer line 1
               // printerCommands.printText("\n\n" +receipt.getFooter().getLine2() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL); // footer line 2
            }

            printerCommands.printText(dashedLine + "\n");
//            if (businessDetails.getAddress() != null && !businessDetails.getAddress().isEmpty()) {
//                stringBuilder = new StringBuilder();
//                stringBuilder.append(Utils.wrap(businessDetails.getAddress() , is58mm, Utils.DOUBLE_WIDTH, 0));
//                stringBuilder.append("\n");
//                printerCommands.printText(stringBuilder.toString(), EscPosPrinterCommands.TEXT_SIZE_NORMAL);// business address
//            }
//            if (businessDetails.getWebsite() != null && !businessDetails.getWebsite().isEmpty())
//                printerCommands.printText(businessDetails.getWebsite() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL); // business website
//
//            if (businessDetails.getEmail() != null && !businessDetails.getEmail().isEmpty())
//                printerCommands.printText(businessDetails.getEmail() + "\n\n\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);// business email

            //Thread.sleep(1000);
            printerCommands.printText("\n\n\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
            printerCommands.feedPaper(3);
            printerCommands.cutPaper();

            Log.e(TAG, "printReceipt: print ended ");
           // Thread.sleep(1000);
            //     printerConnection.disconnect();
        } catch (EscPosConnectionException e) {
            e.printStackTrace();
        } catch (EscPosEncodingException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }


    }

    private static List<String> wrapText(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        // Split by newline to get paragraphs. Keep empty paragraphs (they represent intentional blank lines).
        String[] paragraphs = text.split("\n", -1); // -1 keeps trailing empty strings

        for (String para : paragraphs) {
            // If paragraph is empty, user wanted an intentional blank line -> add one empty entry.
            if (para.isEmpty()) {
                lines.add(""); // one blank line only
                continue;
            }

            String[] words = para.split(" ");
            StringBuilder currentLine = new StringBuilder();

            for (String word : words) {
                String testLine = currentLine.length() > 0 ? currentLine + " " + word : word;
                float width = paint.measureText(testLine);

                if (width <= maxWidth) {
                    if (currentLine.length() > 0) currentLine.append(" ");
                    currentLine.append(word);
                } else {
                    if (currentLine.length() > 0) {
                        lines.add(currentLine.toString());
                        currentLine = new StringBuilder(word);
                    } else {
                        // Word itself is longer than maxWidth -> break it
                        int start = 0;
                        while (start < word.length()) {
                            int end = start;
                            while (end < word.length()
                                    && paint.measureText(word.substring(start, end + 1)) <= maxWidth) {
                                end++;
                            }
                            if (end == start) end++; // force at least 1 char
                            lines.add(word.substring(start, end));
                            start = end;
                        }
                        currentLine = new StringBuilder();
                    }
                }
            }

            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
            // DO NOT add any extra line here — next paragraph start (from split) will handle actual newlines
        }

        return lines;
    }



    // Offer/discount total rows arrive pre-formatted (e.g. "($2.02)") with the
    // currency symbol already inside the string. Those must be printed verbatim
    // instead of being run through Double/Float.parseDouble — which throws a
    // NumberFormatException and aborts the whole receipt image (and then a
    // null-Bitmap NPE). Returns true only for plain, parseable numbers.
    private static boolean isPlainNumericValue(String v) {
        if (v == null) return false;
        String t = v.trim();
        return !t.isEmpty() && t.matches("-?\\d+(\\.\\d+)?");
    }

    private static List<String> wrapText22(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        
        for (String word : words) {
            String testLine = currentLine.toString() + (currentLine.length() > 0 ? " " : "") + word;
            float width = paint.measureText(testLine);
            
            if (width <= maxWidth) {
                currentLine.append(currentLine.length() > 0 ? " " : "").append(word);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                } else {
                    // Word is too long for a single line, force break it
                    int start = 0;
                    while (start < word.length()) {
                        int end = start;
                        while (end < word.length() && paint.measureText(word.substring(start, end + 1)) <= maxWidth) {
                            end++;
                        }
                        if (end == start) end++; // Ensure at least one character
                        lines.add(word.substring(start, end));
                        start = end;
                    }
                }
            }
        }
        
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        
        return lines;
    }

    private static List<String> wrapTextForCenter(String text, Paint paint, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        // Calculate padding (15% on each side)
        int sidePadding = (int)(maxWidth * 0.15);
        int effectiveWidth = maxWidth - (2 * sidePadding);

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        
        for (String word : words) {
            String testLine = currentLine.toString() + (currentLine.length() > 0 ? " " : "") + word;
            float width = paint.measureText(testLine);
            
            if (width <= effectiveWidth) {
                currentLine.append(currentLine.length() > 0 ? " " : "").append(word);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                } else {
                    // Word is too long for a single line, force break it
                    int start = 0;
                    while (start < word.length()) {
                        int end = start;
                        while (end < word.length() && paint.measureText(word.substring(start, end + 1)) <= effectiveWidth) {
                            end++;
                        }
                        if (end == start) end++; // Ensure at least one character
                        lines.add(word.substring(start, end));
                        start = end;
                    }
                }
            }
        }
        
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        
        return lines;
    }

    private static List<String> wrapTextForLeft(String text, Paint paint, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        // Calculate padding (15% on each side)
        int sidePadding = (int)(maxWidth * 0.15);
        int effectiveWidth = maxWidth - (2 * sidePadding);

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        
        for (String word : words) {
            String testLine = currentLine.toString() + (currentLine.length() > 0 ? " " : "") + word;
            float width = paint.measureText(testLine);
            
            if (width <= effectiveWidth) {
                currentLine.append(currentLine.length() > 0 ? " " : "").append(word);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                } else {
                    // Word is too long for a single line, force break it
                    int start = 0;
                    while (start < word.length()) {
                        int end = start;
                        while (end < word.length() && paint.measureText(word.substring(start, end + 1)) <= effectiveWidth) {
                            end++;
                        }
                        if (end == start) end++; // Ensure at least one character
                        lines.add(word.substring(start, end));
                        start = end;
                    }
                }
            }
        }
        
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        
        return lines;
    }

    private static void printEODReport(AsyncEscPosPrinter printer, DeviceConnection printerConnection, boolean is58mm, EodReport eodReport) {

        EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);


        try {
            printerCommands.connect();
            int labelWidth = 1000;
            int fontSize = 65;
            if (!is58mm){
                fontSize = 40;
            }

            LocalDate cdate = LocalTime.now().isBefore(LocalTime.of(5, 0))
                    ? LocalDate.now().minusDays(1)
                    : LocalDate.now();

            String date = cdate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));


//            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
//             String date = sdf.format(new Date());

            ReceiptBuilder receipt = new ReceiptBuilder(labelWidth);
            receipt.setMargin(20, 0).
                    setAlign(Paint.Align.CENTER).
                    setColor(Color.BLACK).
                    setTextSize(fontSize).
                     // setTypeface(this, "fonts/regular.ttf").
                          setTypeface(mContext, "fonts/bold.ttf").
                    addText(eodReport.getEodHeader().getMerchantName()).
                    addText("\n").
                    addText("Day End Report").
                    addText("\n").
                    setTypeface(mContext, "fonts/regular.ttf").
                    setAlign(Paint.Align.LEFT).
                    addText("Date: "+date).
                    // addText("Start Date: "+eodReport.getEodHeader().getStartTime()).
                    // addText("End Date: "+eodReport.getEodHeader().getEndTime()).
                    addBlankSpace(30).
                    addLine().
                    addBlankSpace(30);

            //   addImage(barcode);



            //Success Orders
            Orders successOrders = eodReport.getOrderReport().getSuccessOrders();
            if (false) { //successOrders != null
                List<EODRow> eodSuccessList = new ArrayList<>();

                if (successOrders.getDelivery() != null) {
                    eodSuccessList.add(new EODRow("Delivery", successOrders.getDelivery().getCount(), successOrders.getDelivery().getTotal()));

                }

                if (successOrders.getDineIn() != null) {
                    eodSuccessList.add(new EODRow("Dine In", successOrders.getDineIn().getCount(), successOrders.getDineIn().getTotal()));
                }

                if (successOrders.getPickup() != null) {
                    eodSuccessList.add(new EODRow("Take Away", successOrders.getPickup().getCount(), successOrders.getPickup().getTotal()));
                }

                if (successOrders.getInStore() != null) {
                    eodSuccessList.add(new EODRow("In Store", successOrders.getInStore().getCount(), successOrders.getInStore().getTotal()));
                }

                eodSuccessList.add(new EODRow(true));


                if (successOrders.getTotal() != null) {
                    eodSuccessList.add(new EODRow("Total", successOrders.getTotal().getCount(), successOrders.getTotal().getTotal()));
                }

                if (eodSuccessList.size() > 0)
                    receipt = addOrdersInEOD("Completed Orders", eodSuccessList, receipt, is58mm);

            }
            //    eodSuccessList.add(new EODRow(true));

            //Cancelled Orders
            Orders cancelledOrders = eodReport.getOrderReport().getCancelledOrders();
            if (false) {//cancelledOrders != null
                List<EODRow> eodCancelledList = new ArrayList<>();
                if (cancelledOrders.getDelivery() != null) {
                    Log.e(TAG, "printEODReport: cancelled delivery");
                    eodCancelledList.add(new EODRow("Delivery", cancelledOrders.getDelivery().getCount(), cancelledOrders.getDelivery().getTotal()));
                }

                if (cancelledOrders.getDineIn() != null) {
                    eodCancelledList.add(new EODRow("Dine In", cancelledOrders.getDineIn().getCount(), cancelledOrders.getDineIn().getTotal()));
                }


                if (cancelledOrders.getPickup() != null) {
                    eodCancelledList.add(new EODRow("Take Away", cancelledOrders.getPickup().getCount(), cancelledOrders.getPickup().getTotal()));
                }


                if (cancelledOrders.getInStore() != null) {
                    eodCancelledList.add(new EODRow("In Store", cancelledOrders.getInStore().getCount(), cancelledOrders.getInStore().getTotal()));
                }


                if (cancelledOrders.getTotal() != null) {
                    eodCancelledList.add(new EODRow(true));
                    eodCancelledList.add(new EODRow("Total", cancelledOrders.getTotal().getCount(), cancelledOrders.getTotal().getTotal()));
                }

                if (eodCancelledList.size() > 0) {
                    receipt = addOrdersInEOD("Cancelled Orders", eodCancelledList, receipt, is58mm);
                }

            }
            //  eodCancelledList.add(new EODRow(true));


            //Complimentary Orders
            Orders complimentaryOrders = eodReport.getOrderReport().getComplimentaryOrders();
            if (false){//complimentaryOrders != null
                List<EODRow> eodComplimentaryList = new ArrayList<>();
                if (complimentaryOrders.getDelivery() != null){
                    eodComplimentaryList.add(new EODRow("Delivery", complimentaryOrders.getDelivery().getCount(), complimentaryOrders.getDelivery().getTotal()));
                }

                if (complimentaryOrders.getDineIn() != null) {
                    eodComplimentaryList.add(new EODRow("Dine In", complimentaryOrders.getDineIn().getCount(), complimentaryOrders.getDineIn().getTotal()));
                }


                if (complimentaryOrders.getPickup() != null) {
                    eodComplimentaryList.add(new EODRow("Take Away", complimentaryOrders.getPickup().getCount(), complimentaryOrders.getPickup().getTotal()));
                }


                if (complimentaryOrders.getInStore() != null) {
                    eodComplimentaryList.add(new EODRow("In Store", complimentaryOrders.getInStore().getCount(), complimentaryOrders.getInStore().getTotal()));
                }


                if (complimentaryOrders.getTotal() != null) {
                    eodComplimentaryList.add(new EODRow(true));
                    eodComplimentaryList.add(new EODRow("Total", complimentaryOrders.getTotal().getCount(), complimentaryOrders.getTotal().getTotal()));
                }

                if (eodComplimentaryList.size() > 0){
                    receipt = addOrdersInEOD("Non-Chargeable Orders",eodComplimentaryList,receipt,is58mm);
                }

            }



            //Online Orders
            Orders onlineOrders = eodReport.getOrderReport().getOnlineOrders();
            if (false) {//onlineOrders != null
                List<EODRow> eodRowList = new ArrayList<>();

                if (onlineOrders.getUber() != null){
                    eodRowList.add(new EODRow("Uber",onlineOrders.getUber().getCount(),onlineOrders.getUber().getTotal()));
                }

                if (onlineOrders.getUberEats() != null){
                    eodRowList.add(new EODRow("Uber Eats",onlineOrders.getUber().getCount(),onlineOrders.getUber().getTotal()));
                }

                if (onlineOrders.getDoorDash() != null){
                    eodRowList.add(new EODRow("Doordash",onlineOrders.getDoorDash().getCount(),onlineOrders.getDoorDash().getTotal()));
                }

                if (onlineOrders.getSwiggy() != null){
                    eodRowList.add(new EODRow("Swiggy",onlineOrders.getSwiggy().getCount(),onlineOrders.getSwiggy().getTotal()));
                }

                if (onlineOrders.getZomato() != null){
                    eodRowList.add(new EODRow("Zomato",onlineOrders.getZomato().getCount(),onlineOrders.getZomato().getTotal()));
                }

                if (onlineOrders.getSeamless() != null){
                    eodRowList.add(new EODRow("Seamless",onlineOrders.getSeamless().getCount(),onlineOrders.getSeamless().getTotal()));
                }

                if (onlineOrders.getZom() != null){
                    eodRowList.add(new EODRow("Zom",onlineOrders.getZom().getCount(),onlineOrders.getZom().getTotal()));
                }

                if (onlineOrders.getZom() != null){
                    eodRowList.add(new EODRow("Grubhub",onlineOrders.getGrubhub().getCount(),onlineOrders.getGrubhub().getTotal()));
                }

                if (onlineOrders.getZom() != null){
                    eodRowList.add(new EODRow("Uber eats",onlineOrders.getUberEats().getCount(),onlineOrders.getUberEats().getTotal()));
                }

                if (onlineOrders.getTotal() != null){
                    eodRowList.add(new EODRow(true));
                    eodRowList.add(new EODRow("Total",onlineOrders.getTotal().getCount(),onlineOrders.getTotal().getTotal()));
                }


                if (eodRowList.size() > 0){

                    receipt = addOrdersInEOD("Online Orders",eodRowList,receipt,is58mm);
                }

            }



            if (eodReport.getPaymentReport() != null) {

                //Order Payment Details
                if (eodReport.getPaymentReport().getOrderPaymentDetails() != null) {
                    List<EODRow> orderDetailsList = new ArrayList<>();
                    List<OrderPaymentDetail> orderDetails = eodReport.getPaymentReport().getOrderPaymentDetails();
                    Log.e(TAG, "printEODReport: paymentDetails = " + new Gson().toJson(orderDetails));

                    if (orderDetails != null) {
                        for (OrderPaymentDetail d : orderDetails) {
                            Log.d(TAG, "method=" + d.getPaymentMethod() + ", value=" + d.getValue());
                            orderDetailsList.add(new EODRow(d.getPaymentMethod(), "0", d.getValue()));
                        }
                        orderDetailsList.add(new EODRow("", "", ""));
                        if (orderDetailsList.size() > 0) {
                            receipt = addDetailsInEod("Payment Mode\n", orderDetailsList, receipt, is58mm,"PAYMENT_DATA");
                            receipt.addBlankSpace(10);
                        }
                    }
                }

                //Payment Information
                if (eodReport.getPaymentReport().getPaymentInformation() != null) {
                    List<EODRow> orderPaymentList = new ArrayList<>();
                    List<TitleValue> orderPaymentDetails = eodReport.getPaymentReport().getPaymentInformation();
                    Log.e(TAG, "printEODReport: paymentDetails = " + new Gson().toJson(orderPaymentDetails));

                    if (orderPaymentDetails != null) {
                        for (TitleValue d : orderPaymentDetails) {
                            Log.d(TAG, "method=" + d.getTitle() + ", value=" + d.getValue());
                            orderPaymentList.add(new EODRow(d.getTitle(), "0", d.getValue()));
                        }
                        orderPaymentList.add(new EODRow("", "", ""));

                        if (orderPaymentList.size() > 0) {
                            receipt = addDetailsInEod("Sales Summary\n", orderPaymentList, receipt, is58mm,"ORDER_DATA");
                        }
                    }
                }

            }



                    //PaymentDetails paymentDetails = eodReport.getPaymentReport().getOrderPaymentDetails();
                    //Log.e(TAG, "printEODReport: paymentDetails" + new Gson().toJson(paymentDetails));
                    // if (paymentDetails.getOfflineQR() != null) {
                    //     orderDetailsList.add(new EODRow("QR Payment", "0", paymentDetails.getOfflineQR()));
                    // }

                    // if (paymentDetails.getPaymentLinks() != null) {
                    //     orderDetailsList.add(new EODRow("PAYMENT_LINKS", "0", paymentDetails.getPaymentLinks()));
                    // }

                    // if (paymentDetails.getZomatoPro() != null) {
                    //     orderDetailsList.add(new EODRow("Zomato Pro", "0", paymentDetails.getZomatoPro()));
                    // }

                    // if (paymentDetails.getCard() != null) {
                    //     orderDetailsList.add(new EODRow("Card", "0", paymentDetails.getCard()));
                    // }

                    // if (paymentDetails.getSalary() != null) {
                    //     orderDetailsList.add(new EODRow("Salary", "0", paymentDetails.getSalary()));
                    // }

                    // if (paymentDetails.getDue() != null) {
                    //     orderDetailsList.add(new EODRow("Due", "0", paymentDetails.getDue()));
                    // }

                    // if (paymentDetails.getNc() != null) {
                    //     orderDetailsList.add(new EODRow("NC", "0", paymentDetails.getNc()));
                    // }


                    // if (paymentDetails.getNonchargeble() != null) {
                    //     orderDetailsList.add(new EODRow("Non chargeable", "0", paymentDetails.getNonchargeble()));
                    // }

                    // if (paymentDetails.getCash2() != null) {
                    //     orderDetailsList.add(new EODRow("Cash", "0", paymentDetails.getCash2()));
                    // }

                    // if (paymentDetails.getCnp() != null) {
                    //     orderDetailsList.add(new EODRow("Online payment", "0", paymentDetails.getCnp()));
                    // }


                    // if (paymentDetails.getCash() != null) {
                    //     orderDetailsList.add(new EODRow("Cash", "0", paymentDetails.getCash()));
                    // }




//                    orderDetailsList.add(new EODRow(true));
//                    orderDetailsList.add(new EODRow("Total", "0", "37108.00"));



            //Payment Information
            //if (eodReport.getPaymentReport() != null) {
              //  PaymentDetails paymentInformation = eodReport.getPaymentReport().getPaymentInformation();
              //  List<EODRow> paymentInformationList = new ArrayList<>();
//
//if (paymentInformation != null) {
                   // Log.e(TAG, "printEODReport: paymentInformationList" + new Gson().toJson(paymentInformationList));


                    // if (paymentInformation.getItemTotal() != null) {
                    //     paymentInformationList.add(new EODRow("Item Total", "0", paymentInformation.getItemTotal()));
                    // }

                    // if (paymentInformation.getTax() != null) {
                    //     paymentInformationList.add(new EODRow("Tax", "0", paymentInformation.getTax()));
                    // }

                    // if (paymentInformation.getServiceCharge() != null) {
                    //     paymentInformationList.add(new EODRow("Service Charge", "0", paymentInformation.getServiceCharge()));
                    // }
                    // if (paymentInformation.getDiscount() != null) {
                    //     paymentInformationList.add(new EODRow("Discount", "0", paymentInformation.getDiscount()));
                    // }

                    //  if (paymentInformation.getTip() != null) {
                    //     paymentInformationList.add(new EODRow("Tip", "0", paymentInformation.getTip()));
                    // }

                    // if (paymentInformation.getAdditionalCharges() != null) {
                    //     paymentInformationList.add(new EODRow("Add. Charges", "0", paymentInformation.getAdditionalCharges()));
                    // }

                    // paymentInformationList.add(new EODRow(true));


                    // if (paymentInformation.getGrandTotal() != null) {
                    //     paymentInformationList.add(new EODRow("Grand Total", "0", paymentInformation.getGrandTotal()));
                    // }

                   // if (paymentInformationList.size() > 0)
                     //   receipt = addDetailsInEod("Payment Information", paymentInformationList, receipt, is58mm);

               // }


           // }


            //Expense Payment Details
            List<EODRow> expenseDetailsList = new ArrayList<>();
            expenseDetailsList.add(new EODRow("From Cash", "0", "3355.00"));
            expenseDetailsList.add(new EODRow("FromBank", "0", "15681.00"));
            expenseDetailsList.add(new EODRow(true));
            expenseDetailsList.add(new EODRow("Total", "0", "19036.00"));

            //Day Start Details
            List<EODRow> dayStartDetailsList = new ArrayList<>();
            dayStartDetailsList.add(new EODRow("User name", "0", "biller"));
            dayStartDetailsList.add(new EODRow("Start date", "0", "2022-07-30"));
            dayStartDetailsList.add(new EODRow("", "0", "05:56:24"));
            dayStartDetailsList.add(new EODRow("Prev. Closing ", "0", "965.00"));
            dayStartDetailsList.add(new EODRow("Curr. Closing ", "0", "965.00"));

            //Day End Details
            List<EODRow> dayEndDetailsList = new ArrayList<>();
            dayEndDetailsList.add(new EODRow("User name", "0", "biller"));
            dayEndDetailsList.add(new EODRow("End date", "0", "2022-07-30"));
            dayEndDetailsList.add(new EODRow("", "0", "05:56:24"));
            dayEndDetailsList.add(new EODRow("Inv. Range ", "0", "8696-8779"));
            dayEndDetailsList.add(new EODRow("Prev. Balance ", "0", "965.00"));
            dayEndDetailsList.add(new EODRow("TOT. Cash ", "0", "7351.00"));
            dayEndDetailsList.add(new EODRow("Other Cash ", "0", "0.00"));
            dayEndDetailsList.add(new EODRow("Cash Top-up ", "0", "0.00"));
            dayEndDetailsList.add(new EODRow("Cash Expense", "0", "3355.00"));
            dayEndDetailsList.add(new EODRow("Withdrawal", "0", "4000.00"));
            dayEndDetailsList.add(new EODRow("Expected Remaining", "0", ""));
            dayEndDetailsList.add(new EODRow("Total", "0", "961.00"));
            dayEndDetailsList.add(new EODRow("Final Total", "0", "961.00"));


            //  receipt = addDetailsInEod("Expense Details", expenseDetailsList, receipt, is58mm);
            // receipt = addDetailsInEod("Day Start Details", dayStartDetailsList, receipt, is58mm);
            //receipt = addDetailsInEod("Day End Details", dayEndDetailsList, receipt, is58mm);

            receipt.addBlankSpace(300);
            Bitmap bitmap = receipt.build();

            int targetWidth = 383; // 48mm printing zone with 203dpi => 383px
            if (!is58mm){
                targetWidth = 631;
            }

            Bitmap rescaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    targetWidth,
                    Math.round(((float) bitmap.getHeight()) * ((float) targetWidth) / ((float) bitmap.getWidth())),
                    true
            );


            printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(rescaledBitmap));

            Log.e(TAG, "printEODReport: here it is>>> 1");
           // printerCommands.feedPaper(1);
            printerCommands.cutPaper();
            Log.e(TAG, "printEODReport: here it is>>> 2");

        } catch (EscPosConnectionException e) {
            e.printStackTrace();
        }

    }

    private static ReceiptBuilder addOrdersInEOD(String orderType, List<EODRow> eodRowList, ReceiptBuilder receipt, boolean is58mm) {


        String column = "Order Type;NOS;TOTAL";

        int[] sizer = new int[]{10, 4, 8};
        int maxer = 22;
        if (!is58mm){
            sizer = new int[]{20, 8, 8};
            maxer = 36;
        }

        String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");

        Table titleTable = new Table(column, ";", sizer);


        EODRow firstRow = eodRowList.get(0);
        Table table = new Table(firstRow.getTitle() + ";" + firstRow.getCount() + ";" + firstRow.getTotal(), ";", sizer);

        for (int i = 1; i < eodRowList.size(); i++) {
            EODRow eodRow = eodRowList.get(i);
            if (eodRow.isDashedLine()) {
                table.addRow(dashedLine);
            } else {
                table.addRow(eodRow.getTitle() + ";" + eodRow.getCount() + ";" + eodRow.getTotal());
            }
        }

//        table.addRow("Pick Up;0;0.00");
//        table.addRow("Online chai free;3;808.00");
//        table.addRow(dashedLine);
//        table.addRow("Total;82;37108.00");


        receipt.
                setTypeface(mContext, "fonts/bold.ttf").
                setAlign(Paint.Align.CENTER).
                addText(orderType).
                setAlign(Paint.Align.LEFT).
                addText(titleTable.getTableText()).
                setTypeface(mContext, "fonts/regular.ttf").
                addMultilineText(table.getTableText(), false).addBlankSpace(30).addLine().addBlankSpace(30);

        return receipt;
    }

    private static ReceiptBuilder addDetailsInEod(String type, List<EODRow> eodRowList, ReceiptBuilder receiptBuilder, boolean is58mm,String dataType) {


        StringBuilder stringBuilder = new StringBuilder();
        String col1 = dataType.equals("ORDER_DATA")  ? "Description"  :  "Payment Type";
        String col2 = "Total";

        int maxer = 22;
        if (!is58mm){
            maxer = 36;
        }

        String titleLine = col1 + String.valueOf(new char[maxer - (col1.length() + col2.length())]).replace("\0", " ") + col2;
        String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");
        for (EODRow row : eodRowList) {
            if (row.isDashedLine()) {
                stringBuilder.append(dashedLine + "\n");
            } else {
                String title = row.getTitle();
                String total = row.getTotal();
                Log.e(TAG, "addDetailsInEod: title: " + title + " total: " + total);
                String line = title + String.valueOf(new char[maxer - (title.length() + total.length())]).replace("\0", " ") + total;
                stringBuilder.append(line + "\n");
            }
        }

        receiptBuilder.
                setTypeface(mContext, "fonts/bold.ttf").
                setAlign(Paint.Align.CENTER).
                addText(type+"\n").
                addText("\n").
                setAlign(Paint.Align.LEFT).
                addText(titleLine).
                addBlankSpace(30).
                setTypeface(mContext, "fonts/regular.ttf").
                addMultilineText(stringBuilder.toString(), false)
                .addBlankSpace(30)
                .addLine().addBlankSpace(30);


        return receiptBuilder;
    }

  private static void printEODItemReport(DeviceConnection deviceConnection, boolean is58mm, List<ItemReport> itemReports) {
        EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(deviceConnection);
        try {
            printerCommands.connect();
            int labelWidth = 1000;
            int fontSize = 50;
            if (!is58mm) {
                fontSize = 35;
            }
            ReceiptBuilder receipt = new ReceiptBuilder(labelWidth);
            receipt.setMargin(20, 0).
                    setAlign(Paint.Align.CENTER).
                    setColor(Color.BLACK).
                    setTextSize(is58mm ? 75 : 65).
                    //  setTypeface(this, "fonts/regular.ttf").
                    setTypeface(mContext, "fonts/bold.ttf").
                            addText("Item Summary Report").
                    setTypeface(mContext, "fonts/regular.ttf").
                    setAlign(Paint.Align.LEFT).
                    setTextSize(fontSize).
                    //addText("Start Date: " + eodReport.getEodHeader().getStartTime()).
                    //addText("End Date: " + eodReport.getEodHeader().getEndTime()).
                            addBlankSpace(30).
                    // addLine().
                            addBlankSpace(30);
            String column = "Item;Qty;Total";
            int[] sizer = new int[]{15, 8, 8};
            int maxer = 31;
            if (!is58mm) {
                sizer = new int[]{24, 8, 8};
                maxer = 40;
            }
            Table titleTable = new Table(column, ";", sizer);
            receipt.setAlign(Paint.Align.LEFT)
                    .setTypeface(mContext, "fonts/bold.ttf")
                    .addText(titleTable.getTableText());
            for (ItemReport itemReport : itemReports) {
                addReportItems(itemReport, receipt, is58mm);
            }
            receipt.addBlankSpace(300);
            Bitmap bitmap = receipt.build();
            int targetWidth = 383; // 48mm printing zone with 203dpi => 383px
            if (!is58mm) {
                targetWidth = 631;
            }
            Bitmap rescaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    targetWidth,
                    Math.round(((float) bitmap.getHeight()) * ((float) targetWidth) / ((float) bitmap.getWidth())),
                    true
            );
            printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(rescaledBitmap));
            Log.e(TAG, "printEODReport: here it is>>> 1");
            // printerCommands.feedPaper(1);
            printerCommands.cutPaper();
        } catch (EscPosConnectionException e) {
            e.printStackTrace();
        }
    }
    private static void addReportItems(ItemReport itemReport, ReceiptBuilder receipt, boolean is58mm) {
        String column = "Item;Qty;Total";
        int[] sizer = new int[]{15, 8, 8};
        int maxer = 31;
        if (!is58mm) {
            sizer = new int[]{24, 8, 8};
            maxer = 40;
        }
        String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");
        ReportItems reportItems = itemReport.getReportItems().get(0);
        receipt.setTypeface(mContext,"fonts/bold.ttf").setAlign(Paint.Align.LEFT)
                .addText(dashedLine).setAlign(Paint.Align.CENTER).addText(itemReport.getCategoryName()).setAlign(Paint.Align.LEFT).addText(dashedLine);
        Table table = new Table(reportItems.getItemName() + ";" + reportItems.getQuantity() + ";" + reportItems.getSubTotal(), ";", sizer);
        String cat = Utils.wrap(itemReport.getCategoryName() + " Sub Total", is58mm, Utils.CUSTOM, 5);
        Log.e(TAG, "addReportItems: cat " + cat);
        String total = "TOTAL" + ";" + itemReport.getTotalItemCount() + ";" + itemReport.getSubTotal();
        Table totalTable = new Table(total, ";", sizer);
//        table.addRow(dashedLine);
//        table.addRow(itemReport.getCategoryName());
//        table.addRow(dashedLine);
        for (int i = 1; i < itemReport.getReportItems().size(); i++) {
            ReportItems r = itemReport.getReportItems().get(i);
            table.addRow(r.getItemName() + ";" + r.getQuantity() + ";" + r.getSubTotal());
        }
        receipt.setTypeface(mContext, "fonts/regular.ttf").
                setAlign(Paint.Align.LEFT).
                addMultilineText(table.getTableText(), false)
                .addBlankSpace(30)
                .setTypeface(mContext, "fonts/bold.ttf")
                .addText(totalTable.getTableText());
            //    .addText(dashedLine);
    }

    private static LinkedHashMap<String, List<Item>> groupItemsByCategoryPreservingOrder(List<Item> items) {
    LinkedHashMap<String, List<Item>> groupedMap = new LinkedHashMap<>();

    for (Item item : items) {
        String category = item.getCategory();
        if (!groupedMap.containsKey(category)) {
            groupedMap.put(category, new ArrayList<>());
        }
        groupedMap.get(category).add(item);
    }

    return groupedMap;
}

    private static byte[] parseFontStyle(String fontStyleString) {
        if (fontStyleString == null || fontStyleString.trim().isEmpty()) {
            return new byte[]{0x1D, 0x21, 0x00}; // default normal font
        }

        fontStyleString = fontStyleString.replace("[", "").replace("]", "").trim();
        String[] parts = fontStyleString.split(",");
        byte[] fontStyle = new byte[parts.length];

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            fontStyle[i] = (byte) Integer.decode(part).intValue();
        }
        return fontStyle;
    }

    /**
     * Append a log line. Forwards to {@link com.magilhub.printnats.android.legacy.logging.LogFileAppender}
     * which caches one BufferedWriter per file rather than opening/closing per call.
     *
     * <p>Behaviour preserved from the legacy implementation:
     * <ul>
     *   <li>Same on-disk path and filename pattern.</li>
     *   <li>Same {@code yyyy-MM-dd HH:mm:ss.SSS} timestamp prefix.</li>
     *   <li>Same {@code PAYMENT_LOG_TAG} / {@code PRINT_LOG_TAG} logcat side-effects.</li>
     * </ul>
     */
    public static void appendLogInNativeComponent(Context context, String fileName, String content) {
        // Preserve logcat side-effects so debug tooling that greps logcat keeps working.
        // Fix 4: downgraded from Log.e to Log.d — these are informational print/payment
        // traces, not errors, so they should not sit at error level (and are strippable
        // from release builds).
        if (fileName != null) {
            if (fileName.equalsIgnoreCase("cpPayment_")) Log.d("PAYMENT_LOG_TAG", content);
            else if (fileName.equalsIgnoreCase("print_")) Log.d("PRINT_LOG_TAG", content);
        }
        com.magilhub.printnats.android.legacy.logging.LogFileAppender.append(context, fileName, content);
    }


    /** KOT batch-note (Fire/VOIDED) visibility — decided ONCE in JS
     *  (useNetworkPrintService.showBatchNote) for ALL KOT templates (thermal + Star).
     *  Native only validates the flag: anything other than "true" hides the note. */
    public static boolean resolveShowBatchNote(Receipt receipt) {
        String flag = receipt.getShowBatchNote();
        return flag != null && "true".equalsIgnoreCase(flag.trim());
    }

    public static AsyncEscPosPrinter getAsyncKotPrinter(Context context, DeviceConnection printerConnection, String stationName, Receipt receipt, boolean isStation, boolean is58mm, int kotSpace) {
        // Template 4: same KOT experience/layout/logic as Star Template 4, ported to thermal/ESC-POS.
        // All thermal transports (Bluetooth/USB/LAN) funnel through here, so this one branch covers them.
        if ("4".equals(receipt.getTemplateNo())) {
            return getAsyncKotPrinterT4(context, printerConnection, stationName, receipt, isStation, is58mm, kotSpace);
        }
        // Template 2 (HBB, MS-1718): Template-4 layout + smaller/title-case items + Fire on updates.
        if ("2".equals(receipt.getTemplateNo())) {
            return getAsyncKotPrinterT2(context, printerConnection, stationName, receipt, isStation, is58mm, kotSpace);
        }
        // Template 5: clone of Template 2, registered under its own template number so it
        // can be selected independently in the merchant portal. Matching counterpart:
        // StarPrintUtil.printStarKotT5 (clone of Star Template 3 with the same DB-driven
        // font size + case brought over).
        if ("5".equals(receipt.getTemplateNo())) {
            return getAsyncKotPrinterT5(context, printerConnection, stationName, receipt, isStation, is58mm, kotSpace);
        }
        appendLogInNativeComponent(context,"print_","Info:: Thermal Print Utils Initiated - stationName: "+stationName);
        Log.e(TAG,"test function call");
        AsyncEscPosPrinter printer;
        EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);

        int maxer = 32;
        int[] sizer = new int[]{4, 28};
        if (!is58mm) {
            sizer = new int[]{4,     42};
            printer = new AsyncEscPosPrinter(printerConnection, 203, 79f, 46);
            maxer = 46;
        } else {
            printer = new AsyncEscPosPrinter(printerConnection, 203, 58f, 46);
        }

        String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");

        String dateTimeString = receipt.getCurrentDate() + " " + receipt.getCurrentTime();
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yy hh:mm a");

        long orderTimestampInUnix = 0;
        Date date = null;

        try {
            date = sdf.parse(dateTimeString);
            appendLogInNativeComponent(context,"print_","Info:: Thermal Print Started at "+sdf.parse(dateTimeString));
            orderTimestampInUnix = date.getTime();
        } catch (ParseException e) {
            appendLogInNativeComponent(context,"print_","Exception:: Thermal Print Utils Time Conversion Exception: "+e.getMessage());
            e.printStackTrace();
        }

        long currentTimeInUnix = System.currentTimeMillis();

        long timeDifferenceMillis = currentTimeInUnix - orderTimestampInUnix;
        long minutesDifference = TimeUnit.MILLISECONDS.toMinutes(timeDifferenceMillis);
        Log.e("PrintUtils - dateTimeString", String.valueOf(dateTimeString));
        Log.e("PrintUtils - currentTimeInUnix", String.valueOf(currentTimeInUnix));
        Log.e("PrintUtils - orderTimestampInUnix", String.valueOf(orderTimestampInUnix));
        Log.e("PrintUtils - minutesDifference", String.valueOf(minutesDifference));
        try {
          //  Log.e(TAG,"The time difference hours."+hoursDifference);
            if(minutesDifference >= 45){
                appendLogInNativeComponent(context,"print_","Info:: Thermal Print Ignored due to time diff >= 45 ");
                return printer;
            }
            else {
               // Log.e(TAG,"The time difference is less than 3 hours."+hoursDifference);
                Log.e(TAG,"Treceipt.getItems().size() < 1 "+(receipt.getItems().size() < 1));
                Log.e(TAG,"TisStation && stationName.length() < 1 "+(isStation && stationName.length() < 1));
                if (receipt.getItems().size() < 1)
                    return printer;
                if (isStation && stationName.length() < 1)
                    return printer;
                printerCommands.connect();
                printerCommands.reset();
                //Log.e(TAG,"The items "+receipt);
                appendLogInNativeComponent(context,"print_","Info:: Thermal Print begin Document for Order.No: "+receipt.getOrderNo());


                //title
               // builder.appendAlignment(ICommandBuilder.AlignmentPosition.Center);
                printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                printerCommands.printText("\n\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);

                DeviceConnection connection = printerConnection;
                byte[] fontStyle;

                String kotFontStyle = receipt.getKotFontStyle();
                String kotFont = receipt.getKotFont();
                if (!kotFontStyle.toString().isEmpty() && kotFontStyle != null) {
                    fontStyle = parseFontStyle(kotFontStyle);
                    //fontStyle = new byte[]+kotFontStyle;
                }else{
                    if ("1".equalsIgnoreCase(kotFont)) {
                        fontStyle = new byte[]{0x1D, 0x21, 0x11};
                    } else if ("2".equalsIgnoreCase(kotFont)) {
                        fontStyle = new byte[]{0x1B, 0x21, 0x20};
                    } else {
                        fontStyle = new byte[]{0x1D, 0x21, 0x11};
                    }
                }

                byte[] textAlignMent;

                String kotAlignment = receipt.getKotAlignmenet();
                if ("TEXT_ALIGN_CENTER".equalsIgnoreCase(kotAlignment)) {
                    textAlignMent = EscPosPrinterCommands.TEXT_ALIGN_CENTER;
                } else if ("TEXT_ALIGN_LEFT".equalsIgnoreCase(kotFont)) {
                    textAlignMent = EscPosPrinterCommands.TEXT_ALIGN_LEFT;
                } else {
                    textAlignMent = EscPosPrinterCommands.TEXT_ALIGN_LEFT;
                }

                connection.write(fontStyle);
//                connection.write(
//                        !receipt.getKotFont().isEmpty() && receipt.getKotFont().equalsIgnoreCase("1")?
//                                new byte[]{0x1B, 0x21, 0x20}:
//                                !receipt.getKotFont().isEmpty() && receipt.getKotFont().equalsIgnoreCase("2")?
//                                        new byte[]{0x1B, 0x21, 0x30} :
//                                        new byte[]{0x1B, 0x21, 0x30});
                if(!receipt.getIsAutoPrint())
                {
                    printerCommands.printText("" + "REPRINTED", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    appendLogInNativeComponent(context,"print_","Info:: Thermal Print Reprinted KOT for Order.No: "+receipt.getOrderNo());

                }
                if(receipt.getIsEventOrder())
                {
                    printerCommands.printText("\n" + "EVENT", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }
                else if(receipt.getIsScheduled())
                {
                    printerCommands.printText("\n" + "Scheduled order", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                if (!stationName.toString().isEmpty() && receipt.getShowStationName() != null && !receipt.getShowStationName().toString().isEmpty() && receipt.getShowStationName().toString().equalsIgnoreCase("true")) {
                    String tableNameString = isStation ?  "\n" + stationName + "\n" : "\n EXPO \n";
                    printerCommands.printText(tableNameString, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                if (receipt.getOrderSourceName() != null && !receipt.getOrderSourceName().toString().isEmpty()) {
                    printerCommands.printText("\n" + receipt.getOrderSourceName(), EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                } else {
                    String orderType = "\n" + receipt.getOrderTypeGroup();
                    printerCommands.printText(orderType, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    if (!receipt.isPaymentDone() && receipt.getShowPaymentStatus() != null && !receipt.getShowPaymentStatus().toString().isEmpty() && receipt.getShowPaymentStatus().toString().equalsIgnoreCase("true")) {
                        printerCommands.printText("(UP)"+ "\n",EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    }
                    else{
                        printerCommands.printText(" \n",EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    }
                }

                if (receipt.getTableName() != null && !receipt.getTableName().toString().isEmpty()) {
                    String line = "\nTable: " + receipt.getTableName();
                    if ("true".equalsIgnoreCase(String.valueOf(receipt.getShowPartySize())) && receipt.getGuestCount() != null) {
                        line += ",Guest: " + receipt.getGuestCount();
                    }
                    line += "\n";
                    printerCommands.printText(line, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                //table name
//                if (receipt.getTableName() != null && !receipt.getTableName().toString().isEmpty()) {
//                    String tableNameString = "\nTable : " + receipt.getTableName() + "\n";
//                    printerCommands.printText(tableNameString, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
//                }
//
//                if (receipt.getShowPartySize() != null && !receipt.getShowPartySize().toString().isEmpty() && receipt.getShowPartySize().toString().equalsIgnoreCase("true")) {
//                    String tableNameString = "\nParty : " + receipt.getGuestCount() + "\n";
//                    printerCommands.printText(tableNameString, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
//                }


                String orderNo = "Order #" + receipt.getOrderNo() +
                        ((receipt.getOrderType()!= null && receipt.getOrderType().equalsIgnoreCase("D") && receipt.getSortOrder() != null && !receipt.getSortOrder().isEmpty())
                                ? "-" + receipt.getSortOrder()
                                : "") +
                        ((receipt.getBuzzerNo() != null && !receipt.getBuzzerNo().trim().isEmpty())
                                ? "\n  Buzzer #" + receipt.getBuzzerNo()
                                : "");
                String orderDate = "\n " + receipt.getOrderDate() + " ";
                printerCommands.printText(orderDate+"\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);

                if(receipt.getCurrentFormattedDate() != null && !receipt.getCurrentFormattedDate().toString().isEmpty() &&
                        receipt.getShowPrintTime() != null && !receipt.getShowPrintTime().toString().isEmpty() && "true".equalsIgnoreCase(receipt.getShowPrintTime().toString()))
                {
                    String currentDate = "\n Print " + receipt.getCurrentFormattedDate() + " ";
                    printerCommands.printText(currentDate+"\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                if(receipt.getEtaDate() != null && !receipt.getEtaDate().toString().isEmpty() &&
                        receipt.getShowEtaTime() != null && !receipt.getShowEtaTime().toString().isEmpty() && "true".equalsIgnoreCase(receipt.getShowEtaTime().toString()))
                {
                    String time = "\n Pickup " +receipt.getEtaDate()+ " \n";
                    printerCommands.printText(time, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }


                //printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                printerCommands.printText("\n"+orderNo+"\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                if (receipt.getKotNo() != null && !receipt.getKotNo().toString().isEmpty() && receipt.getShowKotNumber() != null && !receipt.getShowKotNumber().toString().isEmpty() && receipt.getShowKotNumber().equalsIgnoreCase("true")) {
                    String tableNameString = "KOT #" + receipt.getKotNo()+"\n";
                    printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    printerCommands.printText(tableNameString, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                if (receipt.getServerStaffName() != null &&  receipt.getShowStaffNameInKOT() != null && !receipt.getShowStaffNameInKOT().toString().isEmpty() && "true".equalsIgnoreCase(receipt.getShowStaffNameInKOT().toString())) {
                    String staffName = receipt.getServerStaffName() != null ? "\n" + " Staff " + receipt.getServerStaffName() :"";
                    printerCommands.printText(staffName, EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);


                //customer details
                if (receipt.getFullName() != null && !receipt.getFullName().isEmpty()) {
                    printerCommands.printText("\n " + receipt.getFullName(), EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }
                if (receipt.getPhone() != null && !receipt.getPhone().isEmpty()) {
                    printerCommands.printText("\n " + receipt.getPhone() + " \n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }
                

                if (receipt.getTabName()!=null && !receipt.getTabName().toString().isEmpty() ) {
                    printerCommands.printText("\n " +receipt.getTabName() + " \n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }
                if (receipt.getComment() != null && !receipt.getComment().isEmpty() && receipt.getComment().length()>2) {

                    printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                    String orderNote = receipt.getComment();
                    printerCommands.printText(orderNote , EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH,EscPosPrinterCommands.TEXT_COLOR_BLACK, null,EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
                    printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                    String comment = receipt.getComment();
                    printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
//                    int maxWidth = is58mm ? 32 : 46;
//                    int padding = (maxWidth - comment.length()) / 2;
//                    String centeredComment = String.format("%" + padding + "s%s", "", comment);
//                    printerCommands.printText(
//                            "\n" + centeredComment,
//                            EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH,
//                            EscPosPrinterCommands.TEXT_COLOR_BLACK,
//                            null,
//                            EscPosPrinterCommands.TEXT_WEIGHT_BOLD
//                    );
//
//                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
//                    printerCommands.printText(
//                            comment,EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
//                    printerCommands.printText(
//                            comment,
//                            EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH,
//                            EscPosPrinterCommands.TEXT_COLOR_BLACK,
//                            null,
//                            EscPosPrinterCommands.TEXT_WEIGHT_BOLD
//                    );
                    appendLogInNativeComponent(context,"print_","Info:: Thermal Print Order Note: "+receipt.getComment());
                }

                if (receipt.isOrderCancelled()) {
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                    printerCommands.printText("\n\n Voided  \n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                }

                //printerCommands.printText(isStation ? "Station Front\n" : "Kitchen Front\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
               // printerCommands.printText(receipt.getTableName() != null ? receipt.getTableName() + " " + receipt.getOrderTypeGroup() + "\n" :
                //        receipt.getOrderSourceName() == null ? receipt.getOrderTypeGroup() + "\n" : receipt.getOrderSourceName() + " " + receipt.getOrderTypeGroup() + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                //if (receipt.getServerStaffName() != null) {
                //    printerCommands.printText("Steward: " + receipt.getServerStaffName(), EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                //}

                //printing stationName
                // if (isStation) {
                //     printerCommands.printText("\n" + dashedLine, EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                //     // Category name
                //     printerCommands.printText("\n" + stationName.toUpperCase() + "\n\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                //     printerCommands.printText(dashedLine, EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                // }

                printerCommands.setAlign(textAlignMent);
                printerCommands.printText("\n-----------------------\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);

                printerCommands.setAlign(textAlignMent);
                //printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                //Item Group Printing
               // HashMap<String, List<Item>> groupedMap = groupItemsByCategory(receipt.getItems());
                LinkedHashMap<String, List<Item>> groupedMap = groupItemsByCategoryPreservingOrder(receipt.getItems());
                appendLogInNativeComponent(context,"print_","Info:: Thermal Print Order Items: "+new Gson().toJson(receipt.getItems()));

                for (String cat : groupedMap.keySet()) {
                    printerCommands.setAlign(textAlignMent);

                    for (Item item : Objects.requireNonNull(groupedMap.get(cat))) {

                        //printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH); // reset line
                        printerCommands.setAlign(textAlignMent);
                        printerCommands.printText(kotSpace==2 ? "\n------------------------------------------------" :
                                kotSpace == 1 ?"\n--------------------":
                                        "", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);

                        printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                        printerCommands.setAlign(textAlignMent);
                        printerCommands.printText(item.getQuantity() + " " + item.getItemName().trim(), EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                        appendLogInNativeComponent(context,"print_","Info:: Thermal Print Order Item: "+item.getQuantity() + "  " + item.getItemName());


                        if (item.getOptions() != null && item.getOptions().size() > 0) {
                            for (Option op : item.getOptions()) {
                                String customizationCount = receipt.getIsCustomizationCountRequired() ?  op.getQuantity()+ " X " : "";
                                printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                                printerCommands.setAlign(textAlignMent);
                                printerCommands.printText("   " + customizationCount +  op.getOptionName(), EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                                appendLogInNativeComponent(context,"print_","Info:: Thermal Print Order Item Modifier: "+op.getOptionName());
                            }
                            printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH); // reset line
                        }

                        if (item.getComment() != null && !item.getComment().isEmpty()) {
                            printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
                            String comment = item.getComment();
                            printerCommands.printText(comment , EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                            appendLogInNativeComponent(context,"print_","Info:: Thermal Print Order Item Note: "+item.getComment());
                        }
                        printerCommands.printText("\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH); // reset line
                    }
                    printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);
                }
                printerCommands.printText("\n\n\n\n\n\n\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                appendLogInNativeComponent(context,"print_","--------------------------------------------------");
                printerCommands.feedPaper(3);
                printerCommands.cutPaper();
            }
        } catch (EscPosEncodingException e) {
            appendLogInNativeComponent(context,"print_","Exception:: Thermal Print Utils Exception Order No: "+receipt.getOrderNo()+" - stationName: "+stationName);
            appendLogInNativeComponent(context,"print_","Exception:: Thermal Print Utils Encoding Conversion Exception: "+e.getMessage());
            e.printStackTrace();
            Log.e("Thermal E",e.getMessage());
            return null; // caller must NOT execute() this — nothing was written to the printer
        } catch (EscPosConnectionException e) {
            appendLogInNativeComponent(context,"print_","Exception:: Thermal Print Utils Exception Order No: "+receipt.getOrderNo()+" - stationName: "+stationName);
            appendLogInNativeComponent(context,"print_","Exception:: Thermal Print Utils Printer Conncetion Exception: "+e.getMessage());
            e.printStackTrace();
            Log.e("Thermal E",e.getMessage());
            // See the matching catch in getAsyncKotPrinterT4Impl for why this must be null,
            // not `printer` — the KOT content loop above never ran once connect() threw, so
            // handing the caller a normal-looking printer object let the async execute() step
            // report a false "completed" for a job that never printed.
            return null;
        }


        return printer;
    }

    /** Left/right justify two strings to a fixed column width (Template 4 thermal). */
    private static String kotLeftRight(String left, String right, int width) {
        left = left == null ? "" : left.replace("\n", "").trim();
        right = right == null ? "" : right.replace("\n", "").trim();
        if (left.length() + right.length() > width) {
            int half = Math.max(1, width / 2);
            if (left.length() > half) left = left.substring(0, half);
            if (left.length() + right.length() > width && right.length() > half) right = right.substring(0, half);
        }
        int spaces = width - (left.length() + right.length());
        if (spaces < 1) spaces = 1;
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < spaces; i++) sb.append(' ');
        sb.append(right);
        return sb.toString();
    }

    /**
     * Thermal/ESC-POS Template 4 — mirrors {@code StarPrintUtil.printStarKotT4}.
     *
     * <p>Same layout, sections and logic as the Star Template 4 KOT, expressed with
     * the DantSu ESC/POS API: {@code TEXT_SIZE_BIG} (2x2) = Star's appendMultiple(2,2),
     * {@code TEXT_COLOR_RED} = Star's appendInvert (which renders RED on the Star's
     * 2-color paper, so we use red font color here rather than black-background reverse),
     * {@code TEXT_WEIGHT_BOLD} = Star's appendEmphasis. Uses the same batched item
     * logging (Fix 1) so the print hot path makes one log call instead of one per item.
     */
    /** Uniform gap (in dots) added after every Template-4 thermal line via ESC J feed. */
    private static final int T4_LINE_GAP_DOTS = 15;

    /**
     * Prints one KOT line then a uniform inter-line gap (ESC J one-shot feed) so spacing is
     * consistent across the whole ticket. Pass text WITHOUT a trailing newline.
     */
    /** ESC ! (font select: 0x01 = font B 9x17, 0x00 = font A 12x24), GS ! (integer width/height
     *  multiplier), ESC E (emphasis). Emitting all three per line means neither font B nor bold
     *  can ever leak into the next line. */
    private static byte[] escGs(int fontSel, int mult) {
        return escGs(fontSel, mult, false);
    }

    private static byte[] escGs(int fontSel, int mult, boolean bold) {
        return new byte[]{0x1B, 0x21, (byte) fontSel, 0x1D, 0x21, (byte) mult, 0x1B, 0x45, (byte) (bold ? 1 : 0)};
    }

    /** Size bytes for one KOT line, by ascending ladder code (see KotLineDesc). size7/"big"
     *  stays DB-driven (kotFontStyle/kotFont); everything else is a fixed font/multiplier. */
    private static byte[] t4SizeBytes(int sizeCode, byte[] BIG, byte[] NORMAL) {
        switch (sizeCode) {
            case 0:  /* size0 */            return escGs(0x01, 0x00);        // B 1x1 — smallest
            case 2:  /* size2 */            return escGs(0x00, 0x00, true);  // A 1x1 + bold
            case 3:  /* size3 */            return escGs(0x01, 0x10);        // B 2w x 1h
            case 4:  /* size4 */            return escGs(0x01, 0x10, true);  // B 2w x 1h + bold
            case KotLineDesc.SIZE_MEDIUM:   return escGs(0x00, 0x01);        // size5 — A double height
            case KotLineDesc.SIZE_MEDIUM2:  return escGs(0x01, 0x11);        // size6 — B 2x2
            case KotLineDesc.SIZE_BIG:      return BIG;                      // size7 — A 2x2 (DB-driven)
            case 8:  /* size8 */            return escGs(0x01, 0x22);        // B 3x3
            case 9:  /* size9 */            return escGs(0x00, 0x22);        // A 3x3
            case 10: /* size10 */           return escGs(0x00, 0x33);        // A 4x4
            case 11: /* size11 */           return escGs(0x00, 0x44);        // A 5x5
            case 12: /* size12 */           return escGs(0x00, 0x55);        // A 6x6
            default:                        return NORMAL;                   // size1 = A 1x1 (small)
        }
    }

    /** Star-side counterpart of {@link #t4SizeBytes} — same ascending ladder code (see
     *  KotLineDesc), expressed as a Star appendMultiple(width, height) pair instead of
     *  ESC/POS bytes. Per the documented equivalence (TEXT_SIZE_BIG (2x2) = Star's
     *  appendMultiple(2,2)), each ESC/POS GS! multiplier nibble maps to width/height - 1,
     *  so this mirrors t4SizeBytes's switch one-for-one. Used by
     *  StarPrintUtil.printStarKotT5 for T2/T5 font-size parity between drivers. Font B
     *  (narrow) and bold aren't representable via appendMultiple alone — those cases
     *  collapse to their width/height pair only; callers already handle bold via
     *  appendEmphasis where needed. */
    public static int[] starSizeMultiple(int sizeCode) {
        switch (sizeCode) {
            case 0:                         return new int[]{1, 1}; // size0 — B 1x1 (narrow, approximated)
            case 2:                         return new int[]{1, 1}; // size2 — A 1x1 + bold (bold via appendEmphasis)
            case 3:                         return new int[]{2, 1}; // size3 — 2w x 1h
            case 4:                         return new int[]{2, 1}; // size4 — 2w x 1h + bold
            case KotLineDesc.SIZE_MEDIUM:   return new int[]{1, 2}; // size5 — double height
            case KotLineDesc.SIZE_MEDIUM2:  return new int[]{2, 2}; // size6 — 2x2
            case KotLineDesc.SIZE_BIG:      return new int[]{2, 2}; // size7 — 2x2 (documented Star equivalence)
            case 8:                         return new int[]{3, 3}; // size8 — 3x3
            case 9:                         return new int[]{3, 3}; // size9 — 3x3
            case 10:                        return new int[]{4, 4}; // size10 — 4x4
            case 11:                        return new int[]{5, 5}; // size11 — 5x5
            case 12:                        return new int[]{6, 6}; // size12 — 6x6
            default:                        return new int[]{1, 1}; // size1 = 1x1 (small)
        }
    }

    private static void t4Line(EscPosPrinterCommands pc, String text, byte[] size, byte[] color) throws EscPosEncodingException, EscPosConnectionException {
        if (color != null) {
            pc.printText(text + "\n", size, color);
        } else {
            pc.printText(text + "\n", size);
        }
        // Uniform inter-line gap via ESC J one-shot feed. ~15 dots ≈ half of a full blank
        // line (~30). Single knob for the whole ticket — tune up for airier, down for tighter.
        pc.feedPaper(T4_LINE_GAP_DOTS);
    }

    /**
     * One KOT line: text + size (big/normal) + alignment (center/left) + red highlight. This is the
     * SINGLE SOURCE OF TRUTH for the Template-4 KOT layout — the printer emits it via t4Line and the
     * Save Print Snap feature renders the SAME list to a bitmap (SnapPrintUtil). Change the layout
     * here once and both outputs update.
     */
    public static class KotLineDesc {
        // Size ladder — ASCENDING, size0 smallest .. size12 largest. Thermal text mode only has
        // integer multiples of the two firmware fonts (A 12x24 dots, B 9x17), so this is every
        // real step the hardware offers, sorted by printed size:
        //   size0  B 1x1  (9x17)   — smaller than small
        //   size1  A 1x1  (12x24)  = "small"
        //   size2  A 1x1 + bold
        //   size3  B 2w x 1h (18x17) — wider, slightly shorter
        //   size4  B 2w x 1h + bold
        //   size5  A 1w x 2h (12x48) = "medium" (tall/narrow)
        //   size6  B 2x2  (18x34)  = "medium2" (~1.5x proportional)
        //   size7  A 2x2  (24x48)  = "big" (DB-driven bytes via kotFontStyle/kotFont)
        //   size8  B 3x3  (27x51)
        //   size9  A 3x3  (36x72)
        //   size10 A 4x4 | size11 A 5x5 | size12 A 6x6
        public static final int SIZE_MIN = 0;
        public static final int SIZE_NORMAL = 1;
        public static final int SIZE_MEDIUM = 5;
        public static final int SIZE_MEDIUM2 = 6;
        public static final int SIZE_BIG = 7;
        public static final int SIZE_MAX = 12;

        public final String text;
        public final int size;
        public final boolean center;
        public final boolean red;
        public KotLineDesc(String text, int size, boolean center, boolean red) {
            this.text = text; this.size = size; this.center = center; this.red = red;
        }
        public KotLineDesc(String text, boolean big, boolean center, boolean red) {
            this(text, big ? SIZE_BIG : SIZE_NORMAL, center, red);
        }
    }

    /** Map uiFeatureFlags.kotItemFontSize to the ascending ladder: "size0".."size12", or the
     *  aliases small(=size1) | medium(=size5) | medium2(=size6) | big(=size7). */
    public static int kotItemSize(String cfg, boolean defaultBig) {
        String s = cfg == null ? "" : cfg.trim().toLowerCase();
        if ("small".equals(s)) return KotLineDesc.SIZE_NORMAL;
        if ("medium".equals(s)) return KotLineDesc.SIZE_MEDIUM;
        if ("medium2".equals(s)) return KotLineDesc.SIZE_MEDIUM2;
        if ("big".equals(s)) return KotLineDesc.SIZE_BIG;
        if (s.startsWith("size")) {
            try {
                int n = Integer.parseInt(s.substring(4));
                if (n >= KotLineDesc.SIZE_MIN && n <= KotLineDesc.SIZE_MAX) return n;
            } catch (NumberFormatException ignored) {}
        }
        return defaultBig ? KotLineDesc.SIZE_BIG : KotLineDesc.SIZE_NORMAL;
    }

    /** Build the Template-4 KOT as abstract lines (pure — no printer I/O). */
    public static List<KotLineDesc> buildKotLines(Receipt receipt, boolean isStation, String stationName, int maxer,
                                                  int itemSize, String itemTextCase, boolean fireOnAnySortOrder) {
        List<KotLineDesc> L = new ArrayList<>();
        String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");
        String equalLine = String.valueOf(new char[maxer]).replace("\0", "=");

        // DB-driven typography (docs/kot-font-size-components.xlsx): every non-static component's
        // size follows kotItemFontSize (small | medium | big, via itemSize) and its case follows
        // showUpperCaseItemName (upper when true, else as-received). Item/modifier/note lines use
        // kotItemTextCase via applyItemCase. Static (always normal): header, dividers, server
        // name, date/time line, guest count.
        boolean upperItem = receipt.getShowUpperCaseItemName() != null && "true".equalsIgnoreCase(receipt.getShowUpperCaseItemName().toString());

        // Header
        L.add(new KotLineDesc(isStation ? stationName + " KOT" : "Master KOT", false, true, false));
        L.add(new KotLineDesc(equalLine, false, true, false));
        if (receipt.getServerStaffName() != null && !receipt.getServerStaffName().toString().isEmpty()) {
            L.add(new KotLineDesc("Server: " + receipt.getServerStaffName(), false, false, false));
        }
        if (!receipt.getIsAutoPrint()) {
            L.add(new KotLineDesc("REPRINTED", itemSize, true, true));
        }
        if (receipt.getIsEventOrder()) {
            L.add(new KotLineDesc("EVENT", itemSize, true, true));
        } else if (receipt.getIsScheduled()) {
            L.add(new KotLineDesc("SCHEDULED ORDER", itemSize, true, true));
        }
        if (receipt.getOrderSourceName() != null && !receipt.getOrderSourceName().toString().isEmpty()) {
            L.add(new KotLineDesc(caseText(receipt.getOrderSourceName().toString(), upperItem), itemSize, true, true));
        } else {
            L.add(new KotLineDesc(caseText(receipt.getOrderTypeGroup(), upperItem), itemSize, true, true));
        }
        if (receipt.getEtaDate() != null && !receipt.getEtaDate().toString().isEmpty() && receipt.getIsScheduled()) {
            L.add(new KotLineDesc("Pickup " + receipt.getEtaDate(), itemSize, true, true));
        }
        if (!receipt.isPaymentDone() && receipt.getShowPaymentStatus() != null && !receipt.getShowPaymentStatus().toString().isEmpty() && receipt.getShowPaymentStatus().toString().equalsIgnoreCase("true")) {
            L.add(new KotLineDesc("UP", itemSize, true, false));
        }
        L.add(new KotLineDesc(kotLeftRight(receipt.getOrderDate(), receipt.getOrderTime(), maxer), false, false, false));
        L.add(new KotLineDesc(equalLine, false, false, false));

        boolean hasOrderNote = receipt.getComment() != null && !receipt.getComment().toString().isEmpty() && !receipt.getComment().equalsIgnoreCase("-");
        boolean hasBatchNote = resolveShowBatchNote(receipt);
        boolean hasKotBody = (receipt.getFullName() != null && !receipt.getFullName().toString().isEmpty())
                || (receipt.getPhone() != null && !receipt.getPhone().toString().isEmpty())
                || hasOrderNote || hasBatchNote;

        // Table + guests
        if (receipt.getTableName() != null && !receipt.getTableName().toString().isEmpty()) {
            L.add(new KotLineDesc("Table:" + receipt.getTableName(), itemSize, false, false));
            if (receipt.getGuestCount() != null && !receipt.getGuestCount().toString().isEmpty()) {
                L.add(new KotLineDesc("Guests:" + receipt.getGuestCount(), false, false, false));
            }
            if (hasKotBody) L.add(new KotLineDesc(dashedLine, false, false, false));
        }
        if (receipt.getFullName() != null && !receipt.getFullName().toString().isEmpty()) {
            L.add(new KotLineDesc(String.valueOf(receipt.getFullName()), itemSize, false, false));
        }
        if (receipt.getPhone() != null && !receipt.getPhone().toString().isEmpty()) {
            L.add(new KotLineDesc(String.valueOf(receipt.getPhone()), itemSize, false, false));
        }
        if ((receipt.getFullName() != null && !receipt.getFullName().toString().isEmpty()) || (receipt.getPhone() != null && !receipt.getPhone().toString().isEmpty())) {
            L.add(new KotLineDesc(dashedLine, false, false, false));
        }

        if (hasOrderNote) {
            L.add(new KotLineDesc(caseText(receipt.getComment(), upperItem), itemSize, false, true));
            L.add(new KotLineDesc(dashedLine, false, false, false));
        }
        if (hasBatchNote) {
            if (hasOrderNote) L.add(new KotLineDesc(dashedLine, false, false, false));
            L.add(new KotLineDesc(receipt.isOrderCancelled() ? "VOIDED" : caseText(receipt.getBatchNote(), upperItem), itemSize, true, true));
            L.add(new KotLineDesc(dashedLine, false, false, false));
        }


        // Items grouped by category
        LinkedHashMap<String, List<Item>> groupedMap = groupItemsByCategoryPreservingOrder(receipt.getItems());
        List<String> cats = new ArrayList<>();
        for (String k : groupedMap.keySet()) {
            List<Item> it = groupedMap.get(k);
            if (it != null && !it.isEmpty()) cats.add(k);
        }
        for (int ci = 0; ci < cats.size(); ci++) {
            for (Item item : groupedMap.get(cats.get(ci))) {
                String qtyName = item.getQuantity() + " " + item.getItemName();
                L.add(new KotLineDesc(applyItemCase(qtyName, itemTextCase, upperItem), itemSize, false, false));
                if (item.getOptions() != null && item.getOptions().size() > 0) {
                    for (Option op : item.getOptions()) {
                        String[] parts = op.getOptionName().split(":");
                        String cc = receipt.getIsCustomizationCountRequired() ? op.getQuantity() : "";
                        if (parts.length > 1) {
                            L.add(new KotLineDesc(parts[0], itemSize, false, true));
                            L.add(new KotLineDesc(" " + cc + " " + applyItemCase(parts[1], itemTextCase, upperItem), itemSize, false, true));
                        } else {
                            L.add(new KotLineDesc(" " + cc + " " + applyItemCase(op.getOptionName(), itemTextCase, upperItem), itemSize, false, true));
                        }
                    }
                }
                if (item.getComment() != null && !item.getComment().isEmpty()) {
                    L.add(new KotLineDesc("  " + applyItemCase(item.getComment(), itemTextCase, upperItem), itemSize, false, true));
                }
            }
            if (ci < cats.size() - 1) L.add(new KotLineDesc(dashedLine, false, false, false));
        }

        // Footer
        L.add(new KotLineDesc(equalLine, false, true, false));
        // Null-safe by construction: keep the string literal ("D"/"o") as the receiver and
        // getOrderType()/getOrderSource() as the argument, never the other way round.
        // equalsIgnoreCase(null) safely returns false; a null RECEIVER throws NPE instead.
        // orderType/orderSource are null on edit/cancel KOT payloads (SUP-1273) — do not
        // "simplify" this back to receipt.getOrderType().equalsIgnoreCase("D").
        if (receipt.getTransactions() != null && !receipt.getTransactions().isEmpty() && !"D".equalsIgnoreCase(receipt.getOrderType()) && receipt.getOrderSourceName() == null && !receipt.isOrderCancelled() && "o".equalsIgnoreCase(receipt.getOrderSource())) {
            if (receipt.getShowPaymentMethod() != null && "true".equalsIgnoreCase(receipt.getShowPaymentMethod().toString())) {
                for (Transaction t : receipt.getTransactions()) {
                    if ("19".equals(t.getStatusCode())) {
                        String cardType = t.getCardType() != null ? t.getCardType() : "Card";
                        String cardNum = t.getCardLast4()!= null ? t.getCardLast4() :"----";
                        String last4 = cardNum.substring(cardNum.length() - 4);
                        L.add(new KotLineDesc("Payment Method: " + cardType + " XX" + last4, itemSize, true, false));
                    } else if ("24".equals(t.getStatusCode())) {
                        L.add(new KotLineDesc("Payment Method: " + t.getTenderType(), itemSize, true, false));
                    }
                }
            }
            if (receipt.getShowGrandTotal() != null && "true".equalsIgnoreCase(receipt.getShowGrandTotal().toString())) {
                L.add(new KotLineDesc("Grand Total: " + String.format("%.2f", receipt.getOrderTotal()), itemSize, true, false));
            }
        }
        if (receipt.getKotNo() != null && !receipt.getKotNo().toString().isEmpty() && receipt.getShowKotNumber() != null && !receipt.getShowKotNumber().toString().isEmpty() && "true".equalsIgnoreCase(receipt.getShowKotNumber().toString())) {
            L.add(new KotLineDesc("Ticket #: " + receipt.getKotNo(), itemSize, true, true));
        }
        L.add(new KotLineDesc("Order #: " + receipt.getOrderNo(), itemSize, true, true));
        if (receipt.getBuzzerNo() != null && !receipt.getBuzzerNo().trim().isEmpty()) {
            L.add(new KotLineDesc("  Buzzer #: " + receipt.getBuzzerNo(), itemSize, true, true));
        }
        L.add(new KotLineDesc(equalLine, false, true, false));
        return L;
    }

    // Shared Template-4 KOT renderer. Template 2 (HBB, MS-1718) reuses this with a smaller item
    // font + configurable case, and Fire (batch note) shown for any updated batch regardless of
    // order type. getAsyncKotPrinterT4() below keeps Template 4's original look byte-for-byte.
    public static AsyncEscPosPrinter getAsyncKotPrinterT4Impl(Context context, DeviceConnection printerConnection, String stationName, Receipt receipt, boolean isStation, boolean is58mm, int kotSpace,
                                                              int itemSize, String itemTextCase, boolean fireOnAnySortOrder) {
        appendLogInNativeComponent(context, "print_", "Info:: Thermal Print Utils Initiated (T4) - stationName: " + stationName);
        AsyncEscPosPrinter printer;
        EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);

        int maxer = is58mm ? 32 : 46;
        if (!is58mm) {
            printer = new AsyncEscPosPrinter(printerConnection, 203, 79f, 46);
        } else {
            printer = new AsyncEscPosPrinter(printerConnection, 203, 58f, 46);
        }

        // Size/style aliases mapping Star T4 -> ESC/POS.
        // Emphasized ("BIG") size is DB-driven, reusing the SAME logic as the default thermal
        // KOT: a raw kotFontStyle command (parseFontStyle) wins; else the kotFont preset
        // ("2" -> ESC! 0x20); else the default 2x2 (GS! 0x11). Separators stay NORMAL.
        byte[] dbFontStyle;
        String kotFontStyle = receipt.getKotFontStyle();
        String kotFont = receipt.getKotFont();
        if (kotFontStyle != null && !kotFontStyle.isEmpty()) {
            dbFontStyle = parseFontStyle(kotFontStyle);
        } else if ("2".equalsIgnoreCase(kotFont)) {
            dbFontStyle = new byte[]{0x1B, 0x21, 0x20};
        } else {
            dbFontStyle = new byte[]{0x1D, 0x21, 0x11};
        }
        // DB-driven emphasized size, prefixed with ESC E 0 so a bold line (size2b) never leaks in.
        final byte[] BIG = new byte[dbFontStyle.length + 3];
        BIG[0] = 0x1B; BIG[1] = 0x45; BIG[2] = 0x00;
        System.arraycopy(dbFontStyle, 0, BIG, 3, dbFontStyle.length);
        final byte[] NORMAL = escGs(0x00, 0x00);                     // font A, 1x1 (explicit reset)
        // Star's appendInvert renders as RED on the Star's 2-color paper. Use red font color
        // (not reverse/inversion) so thermal matches the Star look — no black boxes.
        final byte[] RED = EscPosPrinterCommands.TEXT_COLOR_RED;
        // NOTE: this printer ignores ESC 3 (line spacing) and ESC J (feed), so spacing is
        // done with a uniform blank line after EVERY line via the t4Line() helper.

        String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");
        String equalLine = String.valueOf(new char[maxer]).replace("\0", "=");

        // 45-minute freshness guard (same as Star T4 / thermal default)
        String dateTimeString = receipt.getCurrentDate() + " " + receipt.getCurrentTime();
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yy hh:mm a");
        long orderTimestampInUnix = 0;
        try {
            Date date = sdf.parse(dateTimeString);
            orderTimestampInUnix = date.getTime();
        } catch (ParseException e) {
            appendLogInNativeComponent(context, "print_", "Exception:: Thermal Print T4 Time Conversion Exception: " + e.getMessage());
        }
        long minutesDifference = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - orderTimestampInUnix);

        try {
            if (minutesDifference >= 45) {
                appendLogInNativeComponent(context, "print_", "Info:: Thermal Print T4 Ignored due to time diff >= 45 ");
                return printer;
            }
            if (receipt.getItems().size() < 1) return printer;
            if (isStation && stationName.length() < 1) return printer;

            printerCommands.connect();
            printerCommands.reset();

            // Single source of truth: build the KOT once (buildKotLines) and emit each line to the
            // printer. SnapPrintUtil renders the SAME list to a bitmap (Save Print Snap) — change the
            // layout in buildKotLines and both the print and the snap update together.
            List<KotLineDesc> kotLines = buildKotLines(receipt, isStation, stationName, maxer, itemSize, itemTextCase, fireOnAnySortOrder);
            for (KotLineDesc d : kotLines) {
                printerCommands.setAlign(d.center ? EscPosPrinterCommands.TEXT_ALIGN_CENTER : EscPosPrinterCommands.TEXT_ALIGN_LEFT);
                t4Line(printerCommands, d.text, t4SizeBytes(d.size, BIG, NORMAL), d.red ? RED : null);
            }
            appendLogInNativeComponent(context, "print_", "Info:: Thermal Print T4 Order " + receipt.getOrderNo() + " (" + kotLines.size() + " lines)");
            printerCommands.printText("\n\n\n", NORMAL);
            printerCommands.feedPaper(3);
            printerCommands.cutPaper();
        } catch (EscPosEncodingException e) {
            appendLogInNativeComponent(context, "print_", "Exception:: Thermal Print T4 Encoding Exception Order " + receipt.getOrderNo() + ": " + e.getMessage());
            e.printStackTrace();
            return null; // caller must NOT execute() this — nothing was written to the printer
        } catch (EscPosConnectionException e) {
            appendLogInNativeComponent(context, "print_", "Exception:: Thermal Print T4 Connection Exception Order " + receipt.getOrderNo() + ": " + e.getMessage());
            e.printStackTrace();
            // Previously fell through to `return printer;` here, which looks like a normal,
            // ready-to-print object. But connect() threw BEFORE a single KOT line was written
            // (the whole loop above is skipped once connect() fails), so nothing ever reached
            // the printer. The caller then still handed this object to AsyncTcpEscPosPrint's
            // own execute() — which does its OWN separate connect (often succeeding a moment
            // later once the printer freed up) and, finding nothing left to send, reported
            // onSuccess anyway. Confirmed live 2026-09-11: Order 042217/KOT 0171's Expo print
            // logged exactly this connect exception, then "Thermal Print ACKNOWLEDGED (printed)"
            // a second later — the dashboard showed "Completed" for a KOT that never printed.
            // Returning null here instead lets the caller (LanUtil/BluetoothUtil/USBUtil) see
            // the real failure and call onFailed() directly, without ever reaching execute().
            return null;
        }

        return printer;
    }

    // Template 4 — original look (big items, dine-in-only Fire). Delegates to the shared impl
    // with behaviour-preserving defaults, so T4 output is unchanged.
    public static AsyncEscPosPrinter getAsyncKotPrinterT4(Context context, DeviceConnection printerConnection, String stationName, Receipt receipt, boolean isStation, boolean is58mm, int kotSpace) {
        // T4 honors kotItemFontSize (small | medium | big) too, but defaults BIG (T2 defaults small).
        int itemSize = kotItemSize(receipt.getKotItemFontSize(), true);
        return getAsyncKotPrinterT4Impl(context, printerConnection, stationName, receipt, isStation, is58mm, kotSpace, itemSize, null, false);
    }

    // Template 2 (HBB, MS-1718) — Template-4 layout with DB-driven item font size + case
    // (uiFeatureFlags.kotItemFontSize: small|medium|big, default small; kotItemTextCase: title|upper|lower,
    // default title) and Fire shown for any updated batch (sortOrder != 1) regardless of order type.
    public static AsyncEscPosPrinter getAsyncKotPrinterT2(Context context, DeviceConnection printerConnection, String stationName, Receipt receipt, boolean isStation, boolean is58mm, int kotSpace) {
        int itemSize = kotItemSize(receipt.getKotItemFontSize(), false); // default: small
        String caseCfg = (receipt.getKotItemTextCase() == null || receipt.getKotItemTextCase().trim().isEmpty())
                ? "title" : receipt.getKotItemTextCase().trim();
        return getAsyncKotPrinterT4Impl(context, printerConnection, stationName, receipt, isStation, is58mm, kotSpace, itemSize, caseCfg, true);
    }

    // Template 5 — clone of Template 2, registered under its own template number so it can
    // be selected independently of T2 in the merchant portal. Identical behavior to T2;
    // exists as a distinct ID so Star's printStarKotT5 (T3 layout + this same DB-driven
    // font size + case) has a matching thermal counterpart.
    public static AsyncEscPosPrinter getAsyncKotPrinterT5(Context context, DeviceConnection printerConnection, String stationName, Receipt receipt, boolean isStation, boolean is58mm, int kotSpace) {
        int itemSize = kotItemSize(receipt.getKotItemFontSize(), false); // default: small
        String caseCfg = (receipt.getKotItemTextCase() == null || receipt.getKotItemTextCase().trim().isEmpty())
                ? "title" : receipt.getKotItemTextCase().trim();
        return getAsyncKotPrinterT4Impl(context, printerConnection, stationName, receipt, isStation, is58mm, kotSpace, itemSize, caseCfg, true);
    }

    // Apply the KOT item text case: "title" | "upper" | "lower". Falls back to the legacy
    // showUpperCaseItemName behaviour (upperFallback) when no explicit case is configured.
    // Public (not private) — StarPrintUtil's Template 5 reuses these directly for T2/T5
    // parity, same cross-file convention as resolveShowBatchNote/kotItemSize.
    /** Case for non-item KOT components: UPPER when showUpperCaseItemName, else as-received. */
    public static String caseText(String s, boolean upper) {
        if (s == null) return "";
        return upper ? s.toUpperCase() : s;
    }

    public static String applyItemCase(String s, String textCase, boolean upperFallback) {
        if (s == null) return "";
        if (textCase != null) {
            if ("title".equalsIgnoreCase(textCase)) return toTitleCase(s);
            if ("upper".equalsIgnoreCase(textCase)) return s.toUpperCase();
            if ("lower".equalsIgnoreCase(textCase)) return s.toLowerCase();
        }
        return upperFallback ? s.toUpperCase() : s.toLowerCase();
    }

    private static String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    //Grouping items by category
    private static HashMap<String, List<Item>> groupItemsByCategory(List<Item> items) {
        HashMap<String, List<Item>> listHashMap = new HashMap<String, List<Item>>();
        for (Item item : items) {
            if (listHashMap.containsKey(item.getCategory())) {
                List<Item> itemList = listHashMap.get(item.getCategory());
                itemList.add(item);
            } else {
                List<Item> itemList = new ArrayList<Item>();
                itemList.add(item);
                listHashMap.put(item.getCategory(), itemList);
            }
        }

        return listHashMap;

    }

    public static AsyncEscPosPrinter getTestAsyncEscPosPrinter(Context context, DeviceConnection printerConnection) {
        mContext = context;
        DeviceConnection mPrinterConnection = printerConnection;

        SimpleDateFormat format = new SimpleDateFormat("'on' yyyy-MM-dd 'at' HH:mm:ss");
        AsyncEscPosPrinter printer = new AsyncEscPosPrinter(printerConnection, 203, 79f, 46);

        EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);
        try {
            printerCommands.connect();
            printerCommands.reset();
            
            // Print test text
            printerCommands.printText("TEST PRINT\n\n\n\n\n\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
            
            printerCommands.feedPaper(4);
            printerCommands.cutPaper();

            
        } catch (EscPosConnectionException e) {
            e.printStackTrace();
        } catch (EscPosEncodingException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return printer;
    }

    public static AsyncEscPosPrinter getAsyncEscPosPrintReceipt(Context context, DeviceConnection printerConnection, String data, boolean is58mm,boolean isTextReceiptPrint) {
        mContext = context;
        mis58mm = is58mm;
        AsyncEscPosPrinter printer = new AsyncEscPosPrinter(printerConnection, 203, 58f, 32);
        appendLogInNativeComponent(context,"print_","Error:: Print Receipt Data: "+new Gson().fromJson(data, Receipt.class));
        if(isTextReceiptPrint){
            Receipt receipt = new Gson().fromJson(data, Receipt.class);
            printReceipt( null,receipt,printerConnection,is58mm,false);
        }else{
            printReceiptFromJson(mContext,printerConnection,data,mis58mm,false);
        }
        return printer;
    }

    public static AsyncEscPosPrinter getAsyncEscPosPrintReceipt1(Context context, DeviceConnection printerConnection, String receipt, boolean is58mm, boolean isSplit) {
        mContext = context;
        mis58mm = is58mm;
        DeviceConnection mPrinterConnection = printerConnection;
        AsyncEscPosPrinter printer = new AsyncEscPosPrinter(mPrinterConnection, 203, 79f, 46);
        int retries = 3;
        while (retries-- > 0) {
            try {
                // Wake-up/keep-alive command: send a line feed (LF) to wake the printer
                try {
                    Log.d(TAG, "Sending wake-up command to printer (LF)...");
                    mPrinterConnection.write(new byte[]{0x0A}); // 0x0A is LF
                } catch (Exception e) {
                    Log.w(TAG, "Wake-up command failed: " + e.getMessage());
                }
                printReceiptFromJson(mContext, mPrinterConnection, receipt, mis58mm, isSplit);
                break; // Success, exit retry loop
            } catch (Exception e) {
                Log.e(TAG, "Printing failed (attempts left: " + retries + "): " + e.getMessage());
                if (retries == 0) {
                    Log.e(TAG, "Printing failed after all retries: " + e.getMessage());
                } else {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        return printer;
    }

    private static Bitmap convertToBlackAndWhite(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();
        Bitmap bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int threshold = 127;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = original.getPixel(x, y);
                int gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                if (gray < threshold) {
                    bw.setPixel(x, y, Color.BLACK);
                } else {
                    bw.setPixel(x, y, Color.WHITE);
                }
            }
        }
        return bw;
    }

    private static Bitmap ditherToBW(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();
        Bitmap bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[][] gray = new int[height][width];

        // Convert to grayscale
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = original.getPixel(x, y);
                gray[y][x] = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
            }
        }

        // Floyd-Steinberg dithering
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int oldPixel = gray[y][x];
                int newPixel = oldPixel < 128 ? 0 : 255;
                bw.setPixel(x, y, newPixel == 0 ? Color.BLACK : Color.WHITE);
                int quantError = oldPixel - newPixel;
                if (x + 1 < width) gray[y][x + 1] += quantError * 7 / 16;
                if (y + 1 < height) {
                    if (x > 0) gray[y + 1][x - 1] += quantError * 3 / 16;
                    gray[y + 1][x] += quantError * 5 / 16;
                    if (x + 1 < width) gray[y + 1][x + 1] += quantError * 1 / 16;
                }
            }
        }
        return bw;
    }


    public static Bitmap convertToBlackAndWhiteDithered(Bitmap original, int targetWidth) {
        // Resize the image to target width, maintaining aspect ratio
        int width = original.getWidth();
        int height = original.getHeight();
        float scale = (float) targetWidth / width;
        int newHeight = Math.round(height * scale);
        Bitmap resized = Bitmap.createScaledBitmap(original, targetWidth, newHeight, true);

        Bitmap bwBitmap = Bitmap.createBitmap(targetWidth, newHeight, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int oldPixel = resized.getPixel(x, y);
                int gray = (int) (0.3 * Color.red(oldPixel) + 0.59 * Color.green(oldPixel) + 0.11 * Color.blue(oldPixel));
                int newColor = gray < 128 ? 0 : 255;
                int error = gray - newColor;

                int bw = Color.rgb(newColor, newColor, newColor);
                bwBitmap.setPixel(x, y, bw);

                // Floyd–Steinberg dithering error diffusion
                if (x + 1 < targetWidth)
                    applyDitherError(resized, x + 1, y, error, 7.0 / 16);
                if (x - 1 >= 0 && y + 1 < newHeight)
                    applyDitherError(resized, x - 1, y + 1, error, 3.0 / 16);
                if (y + 1 < newHeight)
                    applyDitherError(resized, x, y + 1, error, 5.0 / 16);
                if (x + 1 < targetWidth && y + 1 < newHeight)
                    applyDitherError(resized, x + 1, y + 1, error, 1.0 / 16);
            }
        }

        return bwBitmap;
    }

    private static void applyDitherError(Bitmap bmp, int x, int y, int error, double factor) {
        int pixel = bmp.getPixel(x, y);
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);

        int gray = (int) (0.3 * r + 0.59 * g + 0.11 * b);
        gray += error * factor;
        gray = Math.max(0, Math.min(255, gray));
        int corrected = Color.rgb(gray, gray, gray);

        bmp.setPixel(x, y, corrected);
    }

    public static void printReceiptFromJson(Context context, DeviceConnection printerConnection, String receiptJsonString, boolean is58mm, boolean isSplit) {
        try {
            ReceiptPojo receiptJson = new Gson().fromJson(receiptJsonString, ReceiptPojo.class);
            if (receiptJson == null) {
                Log.e(TAG, "Failed to parse JSON into ReceiptPojo");
                return;
            }
            
            Bitmap receiptImage = null;

            EscPosPrinter printer = new EscPosPrinter(printerConnection, 203, 48f, 32);

            try {
                // eodTipConfig is absent on some print payloads (KOT, older
                // events). Reading it unguarded threw inside this try and left
                // receiptImage null, which then NPE'd on getHeight() below.
                boolean isEodTipEnabled = receiptJson.getEodTipConfig() != null && receiptJson.getEodTipConfig().isEodTipEnabled();
                boolean isTransactionReceipt = receiptJson.getEodTipConfig() != null && receiptJson.getEodTipConfig().isTransactionReceipt();
                if(isEodTipEnabled || isTransactionReceipt){
                    receiptImage = generateEodTipReceiptImageFromJson(context, receiptJson, is58mm);
                }else{
                    receiptImage = generateReceiptImageFromJson(context, receiptJson, is58mm);
                }

                int targetWidth = is58mm ? 432 : 576;
                int targetHeight = Math.round(((float) receiptImage.getHeight()) * ((float) targetWidth) / ((float) receiptImage.getWidth()));

                Bitmap scaledImage = Bitmap.createScaledBitmap(
                        receiptImage,
                        targetWidth,
                        targetHeight,
                        true
                );

                Bitmap grayscaleImage = convertToBlackAndWhite(scaledImage);
                printerConnection.write(new byte[]{0x0A});
                EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);
                printerCommands.connect();
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(grayscaleImage));
                printerCommands.cutPaper();

                grayscaleImage.recycle();

            } catch (Exception e) {
                Log.e(TAG, "Error printing receipt: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Always disconnect the printer
                try {
                    printer.disconnectPrinter();
                } catch (Exception e) {
                    Log.e(TAG, "Error disconnecting printer: " + e.getMessage());
                }

                // Recycle the bitmap
                if (receiptImage != null && !receiptImage.isRecycled()) {
                    receiptImage.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in printReceiptFromJson: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Builds ONE horizontal strip bitmap of the accepted payment-method logos
     * for the pay-by-link "Scan QR Code to Pay" block (MHB-33608). Returns
     * null when nothing should be drawn (no context, empty list, or none of
     * the entries map to a known logo).
     *
     * Only the 6 UX-provided logos are ever shown. Each `cards` entry is
     * normalized (lower-cased, all non-alphanumerics stripped) and matched to
     * a drawable; unknown values (Cash, Maestro, ...) are ignored. The row
     * always follows the Figma display order below, NOT the API order.
     *
     * Logos are black-on-transparent PNGs; they are composited on a WHITE
     * background so the same strip prints correctly on both the Canvas image
     * path and the ESC/POS bitmap paths. Row height is fixed by logoHeightPx;
     * the whole row is clamped DOWN if it is wider than maxWidthPx.
     */
    /**
     * Drawable id by name from the host app's merged resources — works whether this module is an AAR (its own R
     * class) or compiled from source into the RN bridge (no com.magilhub.printnats.android.R there). 0 if missing.
     */
    private static int drawableId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
    }

    private static Bitmap buildPaymentMethodsStrip(Context ctx, List<String> cards,
                                                   int logoHeightPx, int maxWidthPx) {
        if (ctx == null || cards == null || cards.isEmpty() || logoHeightPx <= 0) {
            return null;
        }

        // Canonical token -> drawable, in Figma display order.
        LinkedHashMap<String, Integer> catalog = new LinkedHashMap<>();
        catalog.put("mastercard", drawableId(ctx, "pm_mastercard"));
        catalog.put("visa", drawableId(ctx, "pm_visa"));
        catalog.put("amex", drawableId(ctx, "pm_amex"));
        catalog.put("discover", drawableId(ctx, "pm_discover"));
        catalog.put("applepay", drawableId(ctx, "pm_applepay"));
        catalog.put("googlepay", drawableId(ctx, "pm_googlepay"));

        // Normalize what the merchant accepts, folding known aliases.
        java.util.Set<String> accepted = new java.util.HashSet<>();
        for (String c : cards) {
            if (c == null) continue;
            String n = c.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            if (n.isEmpty()) continue;
            if (n.equals("master") || n.equals("mastercards") || n.equals("mc")) {
                n = "mastercard";
            } else if (n.equals("americanexpress")) {
                n = "amex";
            }
            accepted.add(n);
        }
        if (accepted.isEmpty()) return null;

        // Decode + scale each matching logo to a uniform height (Figma order).
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inScaled = false; // ignore density buckets; we scale explicitly
        List<Bitmap> logos = new ArrayList<>();
        int sumW = 0;
        for (java.util.Map.Entry<String, Integer> e : catalog.entrySet()) {
            if (!accepted.contains(e.getKey()) || e.getValue() == 0) continue;
            Bitmap raw = android.graphics.BitmapFactory.decodeResource(
                    ctx.getResources(), e.getValue(), opts);
            if (raw == null) continue;
            int w = Math.max(1, Math.round(raw.getWidth() * (logoHeightPx / (float) raw.getHeight())));
            Bitmap scaled = Bitmap.createScaledBitmap(raw, w, logoHeightPx, true);
            if (scaled != raw) raw.recycle();
            logos.add(scaled);
            sumW += w;
        }
        if (logos.isEmpty()) return null;

        int n = logos.size();
        int targetW = maxWidthPx > 0 ? maxWidthPx : sumW;

        // If the logos plus a minimum breathing gap don't fit the target
        // width, shrink the whole row so they do. Otherwise keep full height.
        int minGap = Math.max(2, logoHeightPx / 4);
        int naturalW = sumW + minGap * Math.max(0, n - 1);
        float k = naturalW > targetW ? targetW / (float) naturalW : 1f;
        int scaledSumW = Math.round(sumW * k);
        int logoH = Math.max(1, Math.round(logoHeightPx * k));

        // Strip spans the FULL target width so the logos spread edge-to-edge
        // (space-around: equal gaps between logos plus half-gaps at the two
        // ends), matching the Figma. A single logo lands centered.
        int stripW = Math.max(scaledSumW, targetW);
        int vPad = Math.max(2, Math.round(logoH * 0.18f));
        int stripH = logoH + vPad * 2;
        float slack = Math.max(0f, stripW - scaledSumW);
        float gap = n > 0 ? slack / n : 0f; // n gaps: half at each end + between

        Bitmap strip = Bitmap.createBitmap(stripW, stripH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(strip);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setFilterBitmap(true);
        p.setDither(true);

        float x = gap / 2f;
        for (Bitmap logo : logos) {
            int w = Math.max(1, Math.round(logo.getWidth() * k));
            Rect dst = new Rect(Math.round(x), vPad, Math.round(x) + w, vPad + logoH);
            c.drawBitmap(logo, null, dst, p);
            x += w + gap;
            logo.recycle();
        }
        return strip;
    }

    private static Bitmap generateReceiptImageFromJson(Context context, ReceiptPojo receiptJson, boolean is58mm) {
        // --- Layout constants for visual match ---
        int printerDPI = 203;
        int paperWidthMM = is58mm ? 58 : 78;
        int paperWidthPixels = (int) ((paperWidthMM * printerDPI) / 25.4);
        int marginPixels = (int) ((1 * printerDPI) / 25.4);
        int width = paperWidthPixels - (marginPixels * 2);
        int padding = (int) ((1 * printerDPI) / 25.4);
        int currentY = padding / 3;  // Reduced initial padding

        // Font sizes (visually matched to image)
        int businessNameSize = (int) (printerDPI * 0.22);
        int orderNoSize = (int) (printerDPI * 0.16);
        int headerSize = (int) (printerDPI * 0.12);
        int dashSize = (int) (printerDPI * 0.12);
        int tableHeaderTitleSize = (int) (printerDPI * 0.14);
        int tableHeaderValueSize = (int) (printerDPI * 0.13);
        int tableHeaderSize = (int) (printerDPI * 0.14);
        int tableRowSize = (int) (printerDPI * 0.14);
        int totalSize = (int) (printerDPI * 0.14);
        int grandTotalSize = (int) (printerDPI * 0.16);
        int footerSize = (int) (printerDPI * 0.12);
        int lineHeight = (int) (printerDPI * 0.13);
        int sectionSpacing = (int) (printerDPI * 0.15);
        int paymentHeaderSize = (int) (printerDPI * 0.16);
        int paymentSecondHeaderSize = (int) (printerDPI * 0.14);
        int paymentValueSize = (int) (printerDPI * 0.12);

        float lineSpacingMultiplier = 1.15f;
        float lineY = currentY + dashSize/2;


        // Load fonts
        Typeface regularFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Regular.ttf");
        Typeface mediumFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Medium.ttf");
        Typeface boldFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Bold.ttf");
        Typeface extraBoldFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-ExtraBold.ttf");

        int estimatedHeight = 4500;
        Bitmap bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        paint.setSubpixelText(true);
        paint.setColor(Color.BLACK);

        try {
            int sidePadding = (int) (width * 0.05);
            int effectiveWidth = width - (2 * sidePadding);
            String logoUrl = null;
            if (receiptJson.getBusinessDetails() != null) {
                logoUrl = receiptJson.getBusinessDetails().getLogo();
            }
            if (logoUrl != null && !logoUrl.trim().isEmpty()) {
                try {
                    android.os.StrictMode.ThreadPolicy policy = new android.os.StrictMode.ThreadPolicy.Builder().permitAll().build();
                    android.os.StrictMode.setThreadPolicy(policy);
                    java.net.URL url = new java.net.URL(logoUrl);
                    android.graphics.Bitmap logoBitmap = android.graphics.BitmapFactory.decodeStream(url.openConnection().getInputStream());
                    if (logoBitmap != null) {
                        int targetWidth = Math.round(effectiveWidth * 0.6f);
                        int targetHeight = Math.round(((float) logoBitmap.getHeight()) * ((float) targetWidth) / ((float) logoBitmap.getWidth()));
                        android.graphics.Bitmap scaledLogo = android.graphics.Bitmap.createScaledBitmap(logoBitmap, targetWidth, targetHeight, true);
                        // Create a new bitmap with a white background and draw the logo on it with full opacity
                        android.graphics.Bitmap opaqueLogo = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888);
                        Canvas logoCanvas = new Canvas(opaqueLogo);
                        logoCanvas.drawColor(Color.WHITE); // Fill with white
                        Paint logoPaint = new Paint();
                        logoPaint.setAlpha(255); // Ensure full opacity
                        logoCanvas.drawBitmap(scaledLogo, 0, 0, logoPaint);
                        float logoX = (width - targetWidth) / 2f;
                        canvas.drawBitmap(opaqueLogo, logoX, currentY, paint);
                        currentY += targetHeight;
                        currentY += sectionSpacing / 2; // Space below logo
                        // Clean up
                        if (scaledLogo != logoBitmap && scaledLogo != null && !scaledLogo.isRecycled()) {
                            scaledLogo.recycle();
                        }
                        if (logoBitmap != null && !logoBitmap.isRecycled()) {
                            logoBitmap.recycle();
                        }
                        if (opaqueLogo != null && !opaqueLogo.isRecycled()) {
                            opaqueLogo.recycle();
                        }
                    }
                } catch (Exception e) {
                }
            }
            // --- Business Name ---
            paint.setTypeface(boldFont);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(businessNameSize);
            String businessName = receiptJson.getBusinessDetails().getName();
            String showBusinessName = receiptJson.getShowRestaurantName();
            if (showBusinessName != null && !showBusinessName.trim().isEmpty() && "true".equalsIgnoreCase(showBusinessName.toString())) {

                // Wrap business name text
                List<String> wrappedLines = wrapTextForCenter(businessName, paint, effectiveWidth);

                // Draw each line of wrapped text
                for (String line : wrappedLines) {
                    paint.setTextAlign(Paint.Align.CENTER);
                    float centerX = width / 2f;
                    canvas.drawText(line, centerX, currentY + businessNameSize, paint);
                    currentY += businessNameSize;
                }
                currentY += sectionSpacing;
            }


            float lineFSpacing = 10f; // pixels between lines
            float lineHHeight = footerSize + lineFSpacing;


            if (receiptJson.getBusinessDetails() != null) {

                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(paymentSecondHeaderSize);

                String address = receiptJson.getBusinessDetails().getAddress() != null ?
                        receiptJson.getBusinessDetails().getAddress() : "";
                String contactNumber = receiptJson.getBusinessDetails().getContactNumber() != null ?
                        receiptJson.getBusinessDetails().getContactNumber() : "";

                if (!address.isEmpty()) {
                    String[] addressLines = address.split(",");
                    currentY += lineHHeight;
                    for (int i = 0; i < addressLines.length; i += 2) {
                        String line1 = addressLines[i].trim();
                        String line2 = (i + 1 < addressLines.length) ? addressLines[i + 1].trim() : "";
                        String combinedLine = line1 + (line2.isEmpty() ? "" : " , " + line2);
                        canvas.drawText(combinedLine.trim(), width / 2, currentY, paint);

                        // move Y for next line
                        currentY += lineHHeight;
                    }
                }

//                        if (!address.isEmpty()) {
//                            String[] addressLines = address.split(",");
//                            for (int i = 0; i < addressLines.length; i += 2) {
//                                String line1 = addressLines[i].trim();
//                                String line2 = (i + 1 < addressLines.length) ? addressLines[i + 1].trim() : "";
//                                String combinedLine = line1 + (line2.isEmpty() ? "" : " , " + line2);
//                                //canvas.drawText(combinedLine.trim(), width / 2, currentY + footerSize, paint);
//                                canvas.drawText(combinedLine.trim(), width / 2, currentY + lineHHeight, paint);
//
//                                if (!line1.isEmpty() && !line2.isEmpty()) {
//                                    currentY += lineHHeight; // extra space
//                                } else {
//                                    currentY += lineHHeight;
//                                }
//                            }
//                        }
                if (!contactNumber.isEmpty()) {
                    canvas.drawText(contactNumber, width / 2, currentY, paint);
                    currentY += footerSize;
                }

                String website = receiptJson.getBusinessDetails().getWebsite() != null ?
                        receiptJson.getBusinessDetails().getWebsite() : "";
                if (!website.isEmpty()) {
                    canvas.drawText(website, width / 2, currentY, paint);
                    currentY += footerSize;
                }

                String email = receiptJson.getBusinessDetails().getEmail() != null ?
                        receiptJson.getBusinessDetails().getEmail() : "";
                if (!email.isEmpty()) {
                    canvas.drawText(email, width / 2, currentY, paint);
                    currentY += footerSize;
                }
                currentY += footerSize;
            }

            String orderTypeGroup = receiptJson.getOrderTypeGroup();
            String orderTable = receiptJson.getTableName();
            if (orderTypeGroup != null && !orderTypeGroup.trim().isEmpty()) {
                String oTypeGroup = orderTypeGroup;
                if (orderTable != null && !orderTable.trim().isEmpty()) {
                    oTypeGroup = orderTypeGroup + " - " + orderTable;
                }
                paint.setTypeface(mediumFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(orderNoSize); // Use same size as order number for visual balance
                canvas.drawText(oTypeGroup.toUpperCase(), width / 2, currentY + orderNoSize, paint);
                currentY += orderNoSize;
                currentY += sectionSpacing; // More space after order type
            }

            String kotNo = receiptJson.getKotNo();
            String showKotNo = receiptJson.getShowKotNumber();
            if (kotNo != null && !kotNo.trim().isEmpty() && showKotNo != null && !showKotNo.toString().isEmpty() && "true".equalsIgnoreCase(showKotNo.toString())) {
                paint.setTypeface(mediumFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(orderNoSize); // Use same size as order number for visual balance
                canvas.drawText("Ticket #" + kotNo, width / 2, currentY + orderNoSize, paint);
                currentY += orderNoSize;
                currentY += sectionSpacing * 1.5f; // More space after order type
            }


            // --- Order Details ---
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(orderNoSize);
            paint.setTypeface(mediumFont);
            paint.setLetterSpacing(0.1f);

            String orderNo = receiptJson.getOrderNo() != null ? "#" + receiptJson.getOrderNo() : "#";
            List<ReceiptPojo.Total> totals = receiptJson.getTotals();
            //canvas.drawText(orderNo, padding, currentY + headerSize, paint);

            // --- Order Information ---
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(headerSize);
            paint.setTypeface(regularFont);
            paint.setLetterSpacing(0.0f);
            String orderedTime = receiptJson.getEtaTime() != null ? receiptJson.getEtaTime() : "";
            String orderNoVal = "";
            String showReceiptNo = receiptJson.getShowReceiptNo();
            if (receiptJson.getOrderNo() != null && !receiptJson.getOrderNo().trim().isEmpty() && showReceiptNo != null && !showReceiptNo.toString().isEmpty() && "true".equalsIgnoreCase(showReceiptNo.toString())) {
                orderNoVal = receiptJson.getOrderNo() != null ? "Order #" + receiptJson.getOrderNo() : "";
            }
            String printedTime = (receiptJson.getOrderDate() != null ? receiptJson.getOrderDate() : "") + (receiptJson.getOrderTime() != null ? (" " + receiptJson.getOrderTime()) : "");
            String staffName = receiptJson.getServerStaffName() != null ? receiptJson.getServerStaffName() : "";
            float leftX = padding;
            float rightX = width - padding;
            float infoY = currentY + headerSize;

            paint.setTypeface(regularFont);
            paint.setTextSize(headerSize);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(orderedTime, leftX, infoY, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(orderNoVal, rightX, infoY, paint);
            currentY += headerSize * lineSpacingMultiplier;
            // Determine currency symbol
            String currencySymbol = "";
            if (receiptJson.getBusinessDetails().getCountry() != null) {
                if (receiptJson.getBusinessDetails().getCountry().equalsIgnoreCase("US")) {
                    currencySymbol = "$";
                } else if (receiptJson.getBusinessDetails().getCountry().equalsIgnoreCase("IN")) {
                    currencySymbol = "₹";
                }
            }
            // infoY = currentY + headerSize;
            // paint.setTypeface(regularFont);
            // paint.setTextSize(headerSize);   
            // paint.setTextAlign(Paint.Align.LEFT);
            // canvas.drawText("Printed " + printedTime, leftX, infoY, paint);
            // paint.setTextAlign(Paint.Align.RIGHT);
            // canvas.drawText("Staff: " + staffName, rightX, infoY, paint);
            // currentY += headerSize * lineSpacingMultiplier;

            // Table name (if present)
            // String table = receiptJson.getTableName() != null ? receiptJson.getTableName() : "";
            // if (table != null && !table.isEmpty()) {
            //     paint.setTextAlign(Paint.Align.LEFT);
            //     canvas.drawText("Table name: " + table, leftX, currentY + headerSize, paint);
            //     currentY += headerSize * lineSpacingMultiplier;
            // }

            // currentY += sectionSpacing/2;

            // // --- Customer Details ---
            // String fullName = receiptJson.getFullName() != null ? receiptJson.getFullName() : "";
            // String phone = receiptJson.getPhone() != null ? receiptJson.getPhone() : "";
            // if (!fullName.isEmpty() || !phone.isEmpty()) {
            //     paint.setTypeface(mediumFont);
            //     String customerInfo = fullName + (!fullName.isEmpty() && !phone.isEmpty() ? "   " : "") + phone;
            //     canvas.drawText(customerInfo, padding, currentY + headerSize, paint);
            //     currentY += headerSize;
            // }

            // currentY += sectionSpacing;

            // --- Items Table ---
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(3);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0)); // Dotted line
            paint.setTypeface(extraBoldFont);
            lineY = currentY + dashSize / 2;
            canvas.drawLine(0, lineY, width, lineY, paint);
            paint.setPathEffect(null); // Reset to solid for other drawing
            paint.setStyle(Paint.Style.FILL);
            currentY += tableHeaderSize;

            // Table Headers
            paint.setTypeface(regularFont);
            paint.setTextSize(tableHeaderTitleSize);

            float availableWidth = width - 2 * padding;
            float itemColWidth = availableWidth * 0.60f;
            float qtyColWidth = availableWidth * 0.20f;
            float totalColWidth = availableWidth * 0.20f;



            float itemX = padding;
            float qtyX = itemX + itemColWidth;
            float totalX = qtyX + qtyColWidth;


                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("ITEM", itemX, currentY + tableHeaderTitleSize, paint);

                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("QTY", qtyX + (qtyColWidth / 2), currentY + tableHeaderTitleSize, paint);

                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("TOTAL", totalX + totalColWidth, currentY + tableHeaderTitleSize, paint);

                currentY += tableHeaderTitleSize * 2;

                // Draw dotted line under headers
//            paint.setStyle(Paint.Style.FILL_AND_STROKE);
//            paint.setStrokeWidth(3);
//            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0)); // Dotted line
//            lineY = currentY + dashSize/2;
//            canvas.drawLine(0, lineY, width, lineY, paint);
//            paint.setPathEffect(null); // Reset to solid for other drawing
//            paint.setStyle(Paint.Style.FILL);
//            currentY += tableHeaderSize;

                // --- Items ---
                paint.setTypeface(regularFont);
                paint.setLetterSpacing(0.0f);
                paint.setTextSize(tableHeaderValueSize);

                boolean isCustomizationCountRequired = receiptJson.isCustomizationCountRequired();

                List<ReceiptPojo.ReceiptItem> items = receiptJson.getItems();
                if (items != null) {
                    for (ReceiptPojo.ReceiptItem item : items) {
                        if (item == null) continue;

                        String itemName = item.getItemName() != null ? item.getItemName() : "";
                        boolean isFreeItem = false;
                        // Append "(Free)" to the item name when this row represents
                        // a redeemed loyalty free_item reward, so the customer can
                        // tell at a glance that the $0.00 price is intentional.
                        if (isFreeItem) {
                            itemName = itemName + " (Free)";
                        }
                        if (item.getIsWeightBased()) {
                            itemName = itemName + "\n(" + currencySymbol + " " + item.getPrice() + "/" + item.getPriceUnit() + ")";
                        }

                        String qty = item.getQuantity() != null ? item.getQuantity() : "0";
                        String total = isFreeItem ? "0.00" : item.getSubTotal() != null ?
                                String.format("%.2f", Double.parseDouble(item.getSubTotal())) : "0.00";
                        String itemPrice = String.valueOf(item.getPrice()) != null ?
                                String.format("%.2f", Double.parseDouble(qty) * item.getPrice()) : "0.00";
                        String displayPrice = isFreeItem
                                ? "0.00"
                                : (isCustomizationCountRequired ? itemPrice : total);

                        // Wrap item name if it's too long
                        List<String> wrappedLines2 = wrapText(itemName, paint, itemColWidth);

                        // Draw first line with QTY and TOTAL
                        paint.setTextAlign(Paint.Align.LEFT);
                        canvas.drawText(wrappedLines2.get(0), itemX, currentY + tableHeaderValueSize, paint);

                        paint.setTextAlign(Paint.Align.RIGHT);
                        canvas.drawText(qty, qtyX + (qtyColWidth / 2), currentY + tableHeaderValueSize, paint);

                        paint.setTextAlign(Paint.Align.RIGHT);
                        canvas.drawText(currencySymbol + displayPrice, totalX + totalColWidth, currentY + tableHeaderValueSize, paint);

                        currentY += tableRowSize;

                        // Draw remaining lines if any
                        for (int j = 1; j < wrappedLines2.size(); j++) {
                            paint.setTextAlign(Paint.Align.LEFT);
                            canvas.drawText(wrappedLines2.get(j), itemX, currentY + tableHeaderValueSize, paint);
                            currentY += tableRowSize;
                        }

                        // Draw options if any
                        List<ReceiptPojo.ItemOption> options = item.getOptions();
                        if (options != null) {
                            for (ReceiptPojo.ItemOption option : options) {
                                if (option == null) continue;

                                String optionName = "   " + (option.getOptionName() != null ? option.getOptionName() : "");
                                String optionQty = resolveOptionQty(option, qty);

                                if (isOptionPriced(option) && isCustomizationCountRequired) {
                                    String optionTotal = String.format("%.2f", resolveOptionTotal(option, qty));
                                    paint.setTextAlign(Paint.Align.LEFT);
                                    canvas.drawText(optionName, itemX, currentY + tableHeaderValueSize, paint);

                                    paint.setTextAlign(Paint.Align.RIGHT);
                                    canvas.drawText(optionQty, qtyX + (qtyColWidth / 2), currentY + tableHeaderValueSize, paint);

                                    paint.setTextAlign(Paint.Align.RIGHT);
                                    canvas.drawText(currencySymbol + optionTotal, totalX + totalColWidth, currentY + tableHeaderValueSize, paint);
                                } else {
                                    paint.setTextAlign(Paint.Align.LEFT);
                                    canvas.drawText(optionName, itemX, currentY + tableHeaderValueSize, paint);
                                }
                                currentY += tableRowSize;
                            }
                        }
                    }
                }


                // Refund Items (Cancelled after payment)
                List<ReceiptPojo.ReceiptItem> refundItemsList = receiptJson.getRefundItems();
                if (refundItemsList != null && !refundItemsList.isEmpty()) {
                    paint.setTypeface(mediumFont);
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText("\n", itemX, currentY + tableHeaderTitleSize, paint);
                    currentY += sectionSpacing;
                    canvas.drawText("Refunded Items", itemX, currentY + tableHeaderTitleSize, paint);
                    currentY += sectionSpacing;
                    currentY = (int) drawReceiptItemsOnCanvas(refundItemsList, canvas, paint, currentY, itemX, qtyX, qtyColWidth, totalX, totalColWidth, itemColWidth, tableHeaderValueSize, tableRowSize, currencySymbol, isCustomizationCountRequired);
                }

                // Voided Items (Cancelled before payment)
                List<ReceiptPojo.ReceiptItem> voidedItems = receiptJson.getVoidedItems();
                if (voidedItems != null && !voidedItems.isEmpty()) {
                    paint.setTypeface(mediumFont);
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText("\n", itemX, currentY + tableHeaderTitleSize, paint);
                    currentY += sectionSpacing;
                    canvas.drawText("Cancelled Items", itemX, currentY + tableHeaderTitleSize, paint);
                    currentY += sectionSpacing;
                    currentY = (int) drawReceiptItemsOnCanvas(voidedItems, canvas, paint, currentY, itemX, qtyX, qtyColWidth, totalX, totalColWidth, itemColWidth, tableHeaderValueSize, tableRowSize, currencySymbol, isCustomizationCountRequired);
                }

                // Legacy: refundedItems (backward compat — only if new arrays are empty)
                List<ReceiptPojo.ReceiptItem> refundedItems = receiptJson.getRefundedItems();
                if (refundedItems != null && !refundedItems.isEmpty()
                        && (voidedItems == null || voidedItems.isEmpty())
                        && (refundItemsList == null || refundItemsList.isEmpty())) {
                    paint.setTypeface(mediumFont);
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText("\nCancelled Items", itemX, currentY + tableHeaderTitleSize, paint);
                    currentY += sectionSpacing;
                    currentY = (int) drawReceiptItemsOnCanvas(refundedItems, canvas, paint, currentY, itemX, qtyX, qtyColWidth, totalX, totalColWidth, itemColWidth, tableHeaderValueSize, tableRowSize, currencySymbol, isCustomizationCountRequired);
                }

                currentY += sectionSpacing / 2;

                // --- Totals ---
                // Dotted line above totals
//            paint.setStyle(Paint.Style.FILL_AND_STROKE);
//            paint.setStrokeWidth(3);
//            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
//            lineY = currentY + dashSize/2;
//            canvas.drawLine(0, lineY, width, lineY, paint);
//            paint.setPathEffect(null);
//            paint.setStyle(Paint.Style.FILL);
//            currentY += tableHeaderSize;
            
            paint.setTypeface(regularFont);
            paint.setTextSize(totalSize);

            if (totals != null) {
                for (ReceiptPojo.Total total : totals) {
                    if (total == null) continue;
                    String title = total.getTitle() != null ? total.getTitle() : "";
                    if (!title.equalsIgnoreCase("Grand Total") && !title.equalsIgnoreCase("TOTAL")) {
                        boolean isLoyaltyRedeemed = isLoyaltyRedeemedTotalCode(total.getCode());
                        boolean valueIsNumeric = isPlainNumericValue(total.getValue());
                        String value = isLoyaltyRedeemed
                                ? formatLoyaltyRedeemedTotalValue(total.getValue(), currencySymbol)
                                : valueIsNumeric
                                    ? String.format("%.2f", Double.parseDouble(total.getValue()))
                                    : (total.getValue() != null ? total.getValue().trim() : "0.00");
                        // A title may contain multiple lines (e.g. a "Discount" label
                        // followed by each applied offer name). Wrap each line to fit
                        // the available width the same way item names wrap (wrapText),
                        // so a long offer name breaks onto new lines instead of
                        // overlapping the value column. Align the amount with the LAST line.
                        String displayValue = (!isLoyaltyRedeemed && valueIsNumeric) ? currencySymbol + value : value;
                        float titleMaxWidth = (width - 2 * padding) - paint.measureText(displayValue) - totalSize;
                        float minTitleWidth = (width - 2 * padding) * 0.4f;
                        if (titleMaxWidth < minTitleWidth) titleMaxWidth = minTitleWidth;
                        List<String> titleLines = wrapText(title, paint, titleMaxWidth);
                        for (int li = 0; li < titleLines.size(); li++) {
                            paint.setTextAlign(Paint.Align.LEFT);
                            canvas.drawText(titleLines.get(li), padding, currentY + totalSize, paint);
                            if (li == titleLines.size() - 1) {
                                paint.setTextAlign(Paint.Align.RIGHT);
                                // Loyalty-redeemed + pre-formatted values already contain
                                // the symbol/brackets; only plain numbers get it prepended.
                                canvas.drawText(displayValue, width - padding, currentY + totalSize, paint);
                            }
                            currentY += totalSize;
                        }
                    }
                }
            }


         // Print refunded amount with currency symbol
            String refundedAmount = receiptJson.getRefundedAmount();
            if(refundedAmount != null && Double.parseDouble(refundedAmount) > 0){
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("Refunded Amount", padding, currentY + totalSize, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(currencySymbol + refundedAmount, width - padding, currentY + totalSize, paint);
                currentY += totalSize;
            }


            currentY += sectionSpacing / 2;

            // Dotted line below totals
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(3);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
                lineY = currentY + dashSize / 2;
                canvas.drawLine(0, lineY, width, lineY, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                currentY += tableHeaderSize;


            // --- Grand Total ---
//            paint.setStyle(Paint.Style.FILL_AND_STROKE);
//            paint.setStrokeWidth(3);
//            lineY = currentY + dashSize/2;
//            canvas.drawLine(0, lineY, width, lineY, paint);
//            paint.setStyle(Paint.Style.FILL);
//            currentY += tableHeaderSize;

            paint.setTypeface(mediumFont);
            paint.setTextSize(grandTotalSize);
            String grandTotalValue = "0.00";
            if (totals != null) {
                for (ReceiptPojo.Total total : totals) {
                    if (total == null) continue;
                    String title = total.getTitle() != null ? total.getTitle() : "";
                    if (title.equalsIgnoreCase("Grand Total") || title.equalsIgnoreCase("TOTAL")) {
                        grandTotalValue = total.getValue() != null ?
                                String.format("%.2f", Double.parseDouble(total.getValue())) : "0.00";
                        break;
                    }
                }
            }
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("TOTAL", padding, currentY + grandTotalSize, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setLetterSpacing(0.1f);
            // Add currency symbol before grand total value
            canvas.drawText(currencySymbol + grandTotalValue, width - padding, currentY + grandTotalSize, paint);
            currentY += grandTotalSize + sectionSpacing;

            // ---- REFUNDED FEE (order activity '109', "Refunded Fee Comp") ----
            // Per Figma this sits BELOW the grand total, fenced by its own
            // dashed rules:  TOTAL / --- dashed --- / REFUNDED FEE block /
            // --- dashed ---. Heading is bold; the lines keep the regular
            // weight of the totals rows. Components are already merged and
            // 2dp-formatted by the JS builder (buildRefundedFeeReceipt) — an
            // order can carry several 109 activities and they print as ONE
            // block.
            ReceiptPojo.RefundedFee refundedFee = receiptJson.getRefundedFee();
            if (refundedFee != null
                    && refundedFee.getLines() != null
                    && !refundedFee.getLines().isEmpty()) {
                // The grand total left the paint at grandTotalSize / medium /
                // letterSpacing 0.1 — reset before drawing anything here.
                paint.setLetterSpacing(0.0f);

                // dashed rule ABOVE
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(3);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
                lineY = currentY + dashSize / 2;
                canvas.drawLine(0, lineY, width, lineY, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                currentY += tableHeaderSize;

                // heading — bold per Figma
                paint.setTypeface(boldFont);
                paint.setTextSize(totalSize);
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("REFUNDED FEE", padding, currentY + totalSize, paint);
                currentY += totalSize + sectionSpacing / 3;

                // component lines — regular weight, roomy line rhythm to match
                paint.setTypeface(regularFont);
                for (ReceiptPojo.RefundedFeeLine line : refundedFee.getLines()) {
                    if (line == null) continue;
                    String name = line.getName() != null ? line.getName() : "";
                    String amount = line.getAmount() != null ? line.getAmount() : "0.00";
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(name, padding, currentY + totalSize, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(currencySymbol + amount, width - padding, currentY + totalSize, paint);
                    currentY += totalSize + sectionSpacing / 4;
                }

                String feeTotal = refundedFee.getTotalAmount();
                if (feeTotal != null && !feeTotal.isEmpty()) {
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText("Refunded Amount", padding, currentY + totalSize, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(currencySymbol + feeTotal, width - padding, currentY + totalSize, paint);
                    currentY += totalSize;
                }

                currentY += sectionSpacing / 2;

                // dashed rule BELOW
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(3);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
                lineY = currentY + dashSize / 2;
                canvas.drawLine(0, lineY, width, lineY, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                currentY += tableHeaderSize;

                // hand the paint back the way the grand total left it, so the
                // loyalty block below renders unchanged.
                paint.setTypeface(mediumFont);
                paint.setTextSize(grandTotalSize);
                paint.setLetterSpacing(0.1f);
            }

            LoyaltyPointReceiptRenderData loyaltyPointReceiptData = getLoyaltyPointReceiptRenderData(receiptJson.getLoyaltyPointReceipt());
            if (loyaltyPointReceiptData.hasContent()) {
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(3);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
                lineY = currentY + dashSize / 2;
                canvas.drawLine(0, lineY, width, lineY, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                currentY += tableHeaderSize;

                paint.setLetterSpacing(0.0f);
                if (loyaltyPointReceiptData.programName != null && !loyaltyPointReceiptData.programName.trim().isEmpty()) {
                    paint.setTypeface(mediumFont);
                    paint.setTextSize(orderNoSize);
                    paint.setTextAlign(Paint.Align.CENTER);
                    List<String> programLines = wrapTextForCenter(loyaltyPointReceiptData.programName.trim(), paint, width - 2 * padding);
                    for (String programLine : programLines) {
                        canvas.drawText(programLine, width / 2f, currentY + orderNoSize, paint);
                        currentY += orderNoSize;
                    }
                    currentY += sectionSpacing / 3;
                }

                if (loyaltyPointReceiptData.paused) {
                    paint.setTypeface(regularFont);
                    float pausedTextSize = Math.max(18f, totalSize - 6);
                    paint.setTextSize(pausedTextSize);
                    paint.setTextAlign(Paint.Align.CENTER);
                    for (String pausedLine : loyaltyPointReceiptData.pausedLines) {
                        List<String> wrappedPausedLines = wrapTextForCenter(pausedLine, paint, width - 2 * padding);
                        for (String wrappedPausedLine : wrappedPausedLines) {
                            canvas.drawText(wrappedPausedLine, width / 2f, currentY + pausedTextSize, paint);
                            currentY += (int) pausedTextSize;
                        }
                    }
                    paint.setTextSize(totalSize);
                } else {
                    // Two-column layout for loyalty rows, modelled on the
                    // items table (ITEM/QTY/TOTAL): the line is split into a
                    // 55% left column (label) and a 45% right column (value).
                    // Each side wraps inside its own column, so a long value
                    // like "earn 3.5x rewards on this kohinoor" stacks on the
                    // right without colliding with the label. Slightly smaller
                    // text size (loyaltyRowSize) so wrapped values stay
                    // compact.
                    paint.setTypeface(regularFont);
                    int loyaltyRowSize = Math.max(18, totalSize - 4);
                    paint.setTextSize(loyaltyRowSize);
                    paint.setTextAlign(Paint.Align.LEFT);

                    float loyaltyAvailable = width - 2 * padding;
                    float loyaltyLeftColWidth = loyaltyAvailable * 0.55f;
                    float loyaltyRightColWidth = loyaltyAvailable * 0.45f;
                    float loyaltyLeftX = padding;
                    float loyaltyRightX = width - padding;
                    float loyaltyLineHeight = loyaltyRowSize * 1.2f;

                    if (loyaltyPointReceiptData.memberValue != null && !loyaltyPointReceiptData.memberValue.isEmpty()) {
                        currentY = drawLoyaltyTwoColumnRow(canvas, paint, "Member", loyaltyPointReceiptData.memberValue,
                                loyaltyLeftX, loyaltyLeftColWidth, loyaltyRightX, loyaltyRightColWidth,
                                currentY, loyaltyRowSize, loyaltyLineHeight);
                        currentY += sectionSpacing / 4;
                    }

                    if (loyaltyPointReceiptData.bonusValue != null && !loyaltyPointReceiptData.bonusValue.isEmpty()) {
                        currentY = drawLoyaltyTwoColumnRow(canvas, paint, loyaltyPointReceiptData.bonusLabel, loyaltyPointReceiptData.bonusValue,
                                loyaltyLeftX, loyaltyLeftColWidth, loyaltyRightX, loyaltyRightColWidth,
                                currentY, loyaltyRowSize, loyaltyLineHeight);
                        currentY += sectionSpacing / 4;
                    }

                    if (loyaltyPointReceiptData.pointsEarnedValue != null && !loyaltyPointReceiptData.pointsEarnedValue.isEmpty()) {
                        currentY = drawLoyaltyTwoColumnRow(canvas, paint, (loyaltyPointReceiptData.pointsLabel + " earned this visit"), loyaltyPointReceiptData.pointsEarnedValue,
                                loyaltyLeftX, loyaltyLeftColWidth, loyaltyRightX, loyaltyRightColWidth,
                                currentY, loyaltyRowSize, loyaltyLineHeight);
                        currentY += sectionSpacing / 5;
                    }

                    if (!loyaltyPointReceiptData.promotionLines.isEmpty()) {
                        float promotionTextSize = Math.max(18f, totalSize - 6);
                        paint.setTextSize(promotionTextSize);
                        for (String promotionLine : loyaltyPointReceiptData.promotionLines) {
                            List<String> wrappedPromotionLines = wrapText(promotionLine, paint, width - (2 * padding));
                            for (String wrappedPromotionLine : wrappedPromotionLines) {
                                canvas.drawText(wrappedPromotionLine, padding, currentY + promotionTextSize, paint);
                                currentY += (int) promotionTextSize;
                            }
                        }
                        paint.setTextSize(loyaltyRowSize);
                        currentY += sectionSpacing / 6;
                    }

                    if (loyaltyPointReceiptData.balanceValue != null && !loyaltyPointReceiptData.balanceValue.isEmpty()) {
                        currentY = drawLoyaltyTwoColumnRow(canvas, paint, "Your balance", loyaltyPointReceiptData.balanceValue,
                                loyaltyLeftX, loyaltyLeftColWidth, loyaltyRightX, loyaltyRightColWidth,
                                currentY, loyaltyRowSize, loyaltyLineHeight);
                    }

                    paint.setTextSize(totalSize);
                }

                currentY += sectionSpacing / 2;
            }

            // --- Pay-QR (uiFeatureFlags.generatePayQr) --------------------
            // "Scan QR Code to Pay" block for UNPAID orders, straight
            // after TOTAL per the Figma. JS sends payQrLink empty for
            // paid/cancelled/sale orders or when the flag is off, so this
            // renders nothing on those receipts. Distinct from the IN-only
            // UPI paymentlink block below and from reviewQRLink.
            String payQrLink = receiptJson.getPayQrLink() != null ? receiptJson.getPayQrLink() : "";
            // TEMP DEBUG (pay-QR-on-paid-order, 2026-09-15) -- remove with the
            // JS "[payqr-gate]" logs. Says what the NATIVE renderer actually
            // received, so we can tell a stale JS bundle (link non-empty here
            // but the JS gate logged NO_QR) from a second render path (no JS
            // gate log at all for this print).
            appendLogInNativeComponent(context, "print_",
                    "[payqr-native] orderNo=" + receiptJson.getOrderNo()
                            + " payQrLink=" + (payQrLink.isEmpty() ? "<empty>" : payQrLink)
                            + " willDrawQrBlock=" + (!payQrLink.isEmpty()));
            if (!payQrLink.isEmpty()) {
                // Dotted divider ABOVE the block (Figma).
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(3);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
                lineY = currentY + dashSize / 2;
                canvas.drawLine(0, lineY, width, lineY, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                currentY += tableHeaderSize;

                paint.setLetterSpacing(0.0f);
                // Caption 1 (UX rev 27 Jul): single BOLD centered line.
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(boldFont);
                paint.setTextSize(headerSize);
                canvas.drawText("Scan QR Code to Pay", width / 2, currentY + headerSize, paint);
                currentY += headerSize + (headerSize / 2);

                // Caption 2 (regular, smaller). Pipe separators on ALL paths
                // (plain ASCII 0x7C) so image and text receipts read
                // identically on every thermal codepage.
                paint.setTypeface(regularFont);
                paint.setTextSize(footerSize);
                canvas.drawText("Fast | Secure | Contactless", width / 2, currentY + footerSize, paint);
                currentY += footerSize + sectionSpacing / 2;

                // QR centered at ~40% paper width (matches the UPI block).
                Bitmap payQrBmp = generateQr(payQrLink);
                int payQrSize = (int) (width * 0.4f);
                Bitmap scaledPayQr = Bitmap.createScaledBitmap(payQrBmp, payQrSize, payQrSize, true);
                canvas.drawBitmap(scaledPayQr, (width - payQrSize) / 2, currentY, paint);
                currentY += payQrSize + sectionSpacing / 2;
                if (scaledPayQr != null && !scaledPayQr.isRecycled()) {
                    scaledPayQr.recycle();
                }
                // Payment-methods strip (MHB-33608): accepted-card logos from
                // restaurantDetails.cards, centered under the QR. Renders
                // nothing when cards is empty or has no known logo. Height
                // tracks the caption; width capped at 80% of the paper.
                Bitmap pmStrip = buildPaymentMethodsStrip(
                        context, receiptJson.getCards(),
                        (int) (printerDPI * 0.22f), (int) (width * 0.96f));
                if (pmStrip != null) {
                    canvas.drawBitmap(pmStrip, (width - pmStrip.getWidth()) / 2f, currentY, paint);
                    currentY += pmStrip.getHeight() + sectionSpacing / 2;
                    if (!pmStrip.isRecycled()) pmStrip.recycle();
                }

                // Dotted divider BELOW the block (Figma) — stays below the
                // payment-icons strip once that lands above this line.
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(3);
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
                lineY = currentY + dashSize / 2;
                canvas.drawLine(0, lineY, width, lineY, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                currentY += tableHeaderSize;
            }

            // --- Payment Info ---
            String paymentLink = receiptJson.getPaymentlink() != null ? receiptJson.getPaymentlink() : "";
            String orderSourceName = receiptJson.getOrderSourceName() != null ? receiptJson.getOrderSourceName() : "";
            if (!paymentLink.isEmpty() && orderSourceName.isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(headerSize);
                paint.setLetterSpacing(0.0f);
                canvas.drawText("Scan To Pay", width / 2, currentY + headerSize, paint);
                currentY += headerSize;
                
                Bitmap qr = generateQr(paymentLink);
                int qrSize = (int)(width * 0.4f);
                Bitmap scaledQr = Bitmap.createScaledBitmap(qr, qrSize, qrSize, true);
                canvas.drawBitmap(scaledQr, (width - qrSize) / 2, currentY, paint);
                currentY += qrSize + sectionSpacing / 2;
                
                if (scaledQr != null && !scaledQr.isRecycled()) {
                    scaledQr.recycle();
                }
            } // Print Payment Info if available
            if (receiptJson.getPaymentStatus() != null && receiptJson.getPaymentStatus().getResponse() != null ) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(paymentHeaderSize);
                paint.setLetterSpacing(0.1f);
                // Center-wrap the 'Payment Info' header
                List<String> paymentHeaderLines = wrapTextForCenter("Payment Info", paint, width - 2 * padding);
                for (String line : paymentHeaderLines) {
                    canvas.drawText(line, width / 2, currentY + paymentHeaderSize, paint);
                    currentY += paymentHeaderSize * lineSpacingMultiplier;
                }

                paint.setTypeface(mediumFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(paymentValueSize);
                paint.setLetterSpacing(0.0f);

                String CardType = (receiptJson.getCardType() != null && !receiptJson.getCardType().toString().isEmpty()) ? receiptJson.getCardType() : receiptJson.getPaymentStatus().getResponse().getCardType() != null ? receiptJson.getPaymentStatus().getResponse().getCardType() :"";
                String ExtraCardInfo = (receiptJson.getCardInfo() != null && !receiptJson.getCardInfo().toString().isEmpty()) ? receiptJson.getCardInfo() :  receiptJson.getPaymentStatus().getResponse().getCardInfo() != null ? receiptJson.getPaymentStatus().getResponse().getCardInfo() : "";
                String CardInfo = ExtraCardInfo.contains(",")  ? ExtraCardInfo.substring(0, ExtraCardInfo.indexOf(",")) : ExtraCardInfo;

                String statusCode = receiptJson.getPaymentStatus().getStatusCode().toString();
                String paidKey = (statusCode.equals("32") || statusCode.equals("26")) ? "Refund:" : statusCode.equals("71")  ? "Authorized:" : "Paid:";
                String paidValue = currencySymbol + receiptJson.getPaymentStatus().getAmountTendered();
                String tipKey =  "Tip:";
                String tipValue = currencySymbol + receiptJson.getPaymentStatus().getResponse().getTipAmount();
                boolean isAppliedTip = receiptJson.getPaymentStatus() != null && receiptJson.getPaymentStatus().getResponse() != null && receiptJson.getPaymentStatus().getResponse().getTipAmount() != null && Double.parseDouble(receiptJson.getPaymentStatus().getResponse().getTipAmount()) > 0;
                String mIdKey = "Merchant Id ";
                String mIdValue = receiptJson.getPaymentStatus().getResponse().getMerchid() != null ? "XXXXXX" + receiptJson.getPaymentStatus().getResponse().getMerchid().substring(receiptJson.getPaymentStatus().getResponse().getMerchid().length() - 4):"" ;
                String maskedCardKey = "Card ";
                String cardNum = receiptJson.getPaymentStatus().getResponse().getToken();
                String last4Digit = cardNum != null && cardNum.length() >= 4 ? cardNum.substring(cardNum.length() - 4) : "";
                String maskedCardValue = CardType + " " + CardInfo +  " "+ (cardNum != null && cardNum.length() >= 4 ? "XX" + last4Digit : "");
                String pIdKey = "Payment Id ";
                String pIdValue = receiptJson.getPaymentStatus().getResponse().getPayApiId() != null ? "XX" + receiptJson.getPaymentStatus().getResponse().getPayApiId().substring( receiptJson.getPaymentStatus().getResponse().getPayApiId().length() - 8):
                    receiptJson.getPaymentStatus().getResponse().getPayApi_Id() != null ? "XX" + receiptJson.getPaymentStatus().getResponse().getPayApi_Id().substring( receiptJson.getPaymentStatus().getResponse().getPayApi_Id().length() - 8):"";
                String authRespKey = "Auth response ";
                String authRespValue = receiptJson.getPaymentStatus().getResponse().getResptext() != null ? receiptJson.getPaymentStatus().getResponse().getResptext() : "";
                String authCodeKey = "Auth code ";
                String authCodeValue = receiptJson.getPaymentStatus().getResponse().getAuthcode() != null ? receiptJson.getPaymentStatus().getResponse().getAuthcode() : "";
                String refIdKey = "Ref Id ";

                String refIdValue = receiptJson.getPaymentStatus().getResponse().getRetref();
                String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                String maskedRef =  (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");

                float keyX = padding;
                float valueX = width - padding;
                float payInfoLineY = currentY + paymentValueSize;
                float lineSpacing = paymentValueSize * lineSpacingMultiplier;
        
                // Paid
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(paidKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(paidValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                if(isAppliedTip){
                    // Tip
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(tipKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(tipValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;
                }
        
                // Merchant Id
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(mIdKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(mIdValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;
        
                // Card
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(maskedCardKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(maskedCardValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;
        
                // Payment Id
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(pIdKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(pIdValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;
        
                // Auth Response
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(authRespKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(authRespValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;
        
                // Auth Code
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(authCodeKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(authCodeValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;
        
                // Ref Id
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(refIdKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(maskedRef, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;
        
                currentY = (int) payInfoLineY;
                currentY += sectionSpacing / 2;
            }else if(receiptJson.getTransactions() != null && !receiptJson.getTransactions().isEmpty() && receiptJson.getTransactions().get(0) != null ) {

                ReceiptPojo.Transaction transaction = receiptJson.getTransactions().get(0);
                Gson gson = new Gson();
                String responseStr = transaction.getResponse();
                Response paymentResponse = gson.fromJson(responseStr, Response.class);

                if (paymentResponse != null && !paymentResponse.toString().isEmpty()) {

                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(mediumFont);
                    paint.setTextSize(paymentHeaderSize);
                    paint.setLetterSpacing(0.1f);
                    // Center-wrap the 'Payment Info' header
                    List<String> paymentHeaderLines = wrapTextForCenter("Payment Info", paint, width - 2 * padding);
                    for (String line : paymentHeaderLines) {
                        canvas.drawText(line, width / 2, currentY + paymentHeaderSize, paint);
                        currentY += paymentHeaderSize * lineSpacingMultiplier;
                    }

                    paint.setTypeface(mediumFont);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(paymentValueSize);
                    paint.setLetterSpacing(0.0f);

                    String CardType = (receiptJson.getCardType() != null && !receiptJson.getCardType().toString().isEmpty()) ? receiptJson.getCardType() : paymentResponse.getCardType() != null ? paymentResponse.getCardType() : "";
                    String ExtraCardInfo = (receiptJson.getCardInfo() !=null && !receiptJson.getCardInfo().toString().isEmpty()) ? receiptJson.getCardInfo() : paymentResponse.getCardInfo() != null ? paymentResponse.getCardInfo() : "";
                    String CardInfo = ExtraCardInfo.contains(",")  ? ExtraCardInfo.substring(0, ExtraCardInfo.indexOf(",")) : ExtraCardInfo;

                    String statusCode = transaction.getStatusCode().toString();
                    String paidKey = (statusCode.equals("32") || statusCode.equals("26")) ? "Refund:" : "Paid:";
                    String paidValue = currencySymbol + transaction.getAmountTendered();
                    String tipKey =  "Tip:";
                    String tipValue = receiptJson.getPaymentStatus() != null ?  currencySymbol + receiptJson.getPaymentStatus().getResponse().getTipAmount() : "0";
                    boolean isAppliedTip = receiptJson.getPaymentStatus() != null && receiptJson.getPaymentStatus().getResponse() != null && receiptJson.getPaymentStatus().getResponse().getTipAmount() != null ?  Double.parseDouble(receiptJson.getPaymentStatus().getResponse().getTipAmount()) > 0 : false;
                    String mIdKey = "Merchant Id ";
                    String mIdValue = paymentResponse.getMerchid() != null ? "XXXXXX" + paymentResponse.getMerchid().substring(paymentResponse.getMerchid().length() - 4) : "";
                    String maskedCardKey = "Card ";
                    String cardNum = paymentResponse.getToken();
                    String last4Digit = cardNum != null && cardNum.length() >= 4 ? cardNum.substring(cardNum.length() - 4) : "";
                    String maskedCardValue = CardType + " " + CardInfo + " " + (cardNum != null && cardNum.length() >= 4 ? "XX" + last4Digit : "");
                    String pIdKey = "Payment Id ";
                    String pIdValue = paymentResponse.getPayApiId() != null ? "XX" + paymentResponse.getPayApiId().substring(paymentResponse.getPayApiId().length() - 8) :
                            paymentResponse.getPayApi_Id() != null ? "XX" + paymentResponse.getPayApi_Id().substring(paymentResponse.getPayApi_Id().length() - 8) : "";
                    String authRespKey = "Auth response ";
                    String authRespValue = paymentResponse.getResptext() != null ? paymentResponse.getResptext() : "";
                    String authCodeKey = "Auth code ";
                    String authCodeValue = paymentResponse.getAuthcode() != null ? paymentResponse.getAuthcode() : "";
                    String refIdKey = "Ref Id ";

                    String refIdValue = paymentResponse.getRetref();
                    String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                    String maskedRef = (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");

                    float keyX = padding;
                    float valueX = width - padding;
                    float payInfoLineY = currentY + paymentValueSize;
                    float lineSpacing = paymentValueSize * lineSpacingMultiplier;

                    // Paid
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(paidKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(paidValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    if(isAppliedTip){
                        // Tip
                        paint.setTextAlign(Paint.Align.LEFT);
                        canvas.drawText(tipKey, keyX, payInfoLineY, paint);
                        paint.setTextAlign(Paint.Align.RIGHT);
                        canvas.drawText(tipValue, valueX, payInfoLineY, paint);
                        payInfoLineY += lineSpacing;
                    }

                    // Merchant Id
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(mIdKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(mIdValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    // Card
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(maskedCardKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(maskedCardValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    // Payment Id
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(pIdKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(pIdValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    // Auth Response
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(authRespKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(authRespValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    // Auth Code
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(authCodeKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(authCodeValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    // Ref Id
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(refIdKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(maskedRef, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;

                    currentY = (int) payInfoLineY;
                    currentY += sectionSpacing / 2;
                }else{
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(mediumFont);
                    paint.setTextSize(paymentSecondHeaderSize);
                    paint.setLetterSpacing(0.0f);
                    String statusCode = receiptJson.getTransactions().get(0).getStatusCode().toString();
                    String paidKey = (statusCode.equals("32") || statusCode.equals("26")) ? "Refund by " : "Paid by ";

                    String info = paidKey + receiptJson.getTransactions().get(0).getTenderType() + " " +
                            String.format("%.2f", receiptJson.getTransactions().get(0).getAmountTendered());
                    //canvas.drawText(info, padding, currentY + paymentSecondHeaderSize, paint);
                    String[] lines = {info};
                    for (String line : lines) {
                        // Optionally wrap if line is long
                        List<String> wrapped = wrapTextForCenter(line, paint, width - 2 * padding);
                        for (String wline : wrapped) {
                            canvas.drawText(wline, width / 2, currentY + paymentValueSize, paint);
                            currentY += paymentValueSize * lineSpacingMultiplier;
                        }
                    }
                    currentY += headerSize + sectionSpacing / 2;
                }
            }


            // --- Footer ---
            float lineSpacing = 10f; // pixels between lines
            float lineFHeight = footerSize + lineSpacing;

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(mediumFont);
            paint.setTextSize(paymentSecondHeaderSize);

            String fullName = receiptJson.getFullName() != null ? receiptJson.getFullName() : "";
            String phone = receiptJson.getPhone() != null ? receiptJson.getPhone() : "";
            if (!fullName.isEmpty() || !phone.isEmpty()) {
                canvas.drawText(fullName, width / 2, currentY + lineFHeight, paint);
                currentY += lineFHeight;
            }
            if(!phone.isEmpty())
            {
                canvas.drawText(phone, width / 2, currentY + lineFHeight, paint);
                currentY += lineFHeight;
            }
            currentY += lineFHeight;

            String reviewQRLink = receiptJson.getReviewQRLink() != null ? receiptJson.getReviewQRLink() : "";
            String reviewMessage = receiptJson.getReviewMessage() != null ? receiptJson.getReviewMessage() : "";
            if (!reviewQRLink.isEmpty() && !reviewMessage.isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(headerSize);
                paint.setLetterSpacing(0.0f);
                 List<String> reviewText = wrapTextForCenter(reviewMessage, paint, width - 2 * padding);
                for (String wline : reviewText) {
                    canvas.drawText(wline, width / 2, currentY + headerSize, paint);
                    currentY += headerSize * lineSpacingMultiplier;
                }

                Bitmap qr = generateQr(reviewQRLink);
                int qrSize = (int)(width * 0.4f);
                Bitmap scaledQr = Bitmap.createScaledBitmap(qr, qrSize, qrSize, true);
                canvas.drawBitmap(scaledQr, (width - qrSize) / 2, currentY, paint);
                currentY += qrSize + sectionSpacing / 2;

                if (scaledQr != null && !scaledQr.isRecycled()) {
                    scaledQr.recycle();
                }
            }

            if (receiptJson.getFooter() != null && receiptJson.getOrderSourceName() == null) {
                List<String> footerText = wrapTextForCenter(receiptJson.getFooter().getLine1(), paint, width - 2 * padding);
                   for (String wline : footerText) {
                        canvas.drawText(wline, width / 2, currentY + lineFHeight, paint);
                        currentY += lineFHeight * lineSpacingMultiplier;
                    }
                currentY += lineFHeight;
            }


            // Add extra space after footer
            currentY += sectionSpacing * 4;
           int finalHeight = currentY + padding;

           // Ensure finalHeight is not more than original bitmap height
           if (finalHeight > bitmap.getHeight()) {
               finalHeight = bitmap.getHeight(); // Avoid crash
           }
           Log.e(TAG, "finalHeight: " + finalHeight);
           Log.e(TAG, "bitmap.getHeight(): " + bitmap.getHeight());
           Log.e(TAG, "currentY + padding: " + (currentY + padding));
           Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, finalHeight);

            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return croppedBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error generating receipt image: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static Bitmap generateEodTipReceiptImageFromJson(Context context, ReceiptPojo receiptJson, boolean is58mm) {
        // --- Layout constants for visual match ---
        int printerDPI = 203;
        int paperWidthMM = is58mm ? 58 : 78;
        int paperWidthPixels = (int) ((paperWidthMM * printerDPI) / 25.4);
        int marginPixels = (int) ((4 * printerDPI) / 25.4);
        int width = paperWidthPixels - (marginPixels * 2);
        int width2 = paperWidthPixels - (marginPixels * 4);
        int padding = (int) ((2 * printerDPI) / 25.4);
        int currentY = padding / 3;  // Reduced initial padding

        // Font sizes (visually matched to image)
        int businessNameSize = (int) (printerDPI * 0.22);
        int businessNameSize2 = (int) (printerDPI * 0.18);
        int orderNoSize = (int) (printerDPI * 0.16);
        int headerSize = (int) (printerDPI * 0.12);
        int dashSize = (int) (printerDPI * 0.12);
        int tableHeaderTitleSize = (int) (printerDPI * 0.14);
        int tableHeaderValueSize = (int) (printerDPI * 0.13);
        int tableHeaderSize = (int) (printerDPI * 0.14);
        int tableRowSize = (int) (printerDPI * 0.14);
        int totalSize = (int) (printerDPI * 0.14);
        int grandTotalSize = (int) (printerDPI * 0.16);
        int footerSize = (int) (printerDPI * 0.12);
        int lineHeight = (int) (printerDPI * 0.13);
        int sectionSpacing = (int) (printerDPI * 0.15);
        int paymentHeaderSize = (int) (printerDPI * 0.16);
        int paymentSecondHeaderSize = (int) (printerDPI * 0.14);
        int paymentValueSize = (int) (printerDPI * 0.12);
        int paymentValueSize2 = (int) (printerDPI * 0.11);

        float lineSpacingMultiplier = 1.15f;
        float lineSpacingTipMultiplier = 1.5f;
        float lineY = currentY + dashSize/2;


        // Load fonts
        Typeface regularFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Regular.ttf");
        Typeface mediumFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Medium.ttf");
        Typeface boldFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Bold.ttf");
        Typeface extraBoldFont = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-ExtraBold.ttf");

        int estimatedHeight = 4500;
        Bitmap bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        paint.setSubpixelText(true);
        paint.setColor(Color.BLACK);

        try {
            int sidePadding = (int) (width * 0.05);
            int effectiveWidth = width - (2 * sidePadding);
            String logoUrl = null;
            if (receiptJson.getBusinessDetails() != null) {
                logoUrl = receiptJson.getBusinessDetails().getLogo();
            }
            if (logoUrl != null && !logoUrl.trim().isEmpty()) {
                try {
                    android.os.StrictMode.ThreadPolicy policy = new android.os.StrictMode.ThreadPolicy.Builder().permitAll().build();
                    android.os.StrictMode.setThreadPolicy(policy);
                    java.net.URL url = new java.net.URL(logoUrl);
                    android.graphics.Bitmap logoBitmap = android.graphics.BitmapFactory.decodeStream(url.openConnection().getInputStream());
                    if (logoBitmap != null) {
                        int targetWidth = Math.round(effectiveWidth * 0.6f);
                        int targetHeight = Math.round(((float) logoBitmap.getHeight()) * ((float) targetWidth) / ((float) logoBitmap.getWidth()));
                        android.graphics.Bitmap scaledLogo = android.graphics.Bitmap.createScaledBitmap(logoBitmap, targetWidth, targetHeight, true);
                        // Create a new bitmap with a white background and draw the logo on it with full opacity
                        android.graphics.Bitmap opaqueLogo = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888);
                        Canvas logoCanvas = new Canvas(opaqueLogo);
                        logoCanvas.drawColor(Color.WHITE); // Fill with white
                        Paint logoPaint = new Paint();
                        logoPaint.setAlpha(255); // Ensure full opacity
                        logoCanvas.drawBitmap(scaledLogo, 0, 0, logoPaint);
                        float logoX = (width - targetWidth) / 2f;
                        canvas.drawBitmap(opaqueLogo, logoX, currentY, paint);
                        currentY += targetHeight;
                        currentY += sectionSpacing / 2; // Space below logo
                        // Clean up
                        if (scaledLogo != logoBitmap && scaledLogo != null && !scaledLogo.isRecycled()) {
                            scaledLogo.recycle();
                        }
                        if (logoBitmap != null && !logoBitmap.isRecycled()) {
                            logoBitmap.recycle();
                        }
                        if (opaqueLogo != null && !opaqueLogo.isRecycled()) {
                            opaqueLogo.recycle();
                        }
                    }
                } catch (Exception e) {
                }
            }
            // --- Business Name ---
            paint.setTypeface(boldFont);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(businessNameSize);
            String businessName = receiptJson.getBusinessDetails() != null ? receiptJson.getBusinessDetails().getName() : "";
            String showBusinessName = receiptJson.getShowRestaurantName();
            if (showBusinessName != null && !showBusinessName.trim().isEmpty() && "true".equalsIgnoreCase(showBusinessName.toString())) {

                // Wrap business name text
                List<String> wrappedLines = wrapTextForCenter(businessName, paint, effectiveWidth);

                // Draw each line of wrapped text
                for (String line : wrappedLines) {
                    paint.setTextAlign(Paint.Align.CENTER);
                    float centerX = width / 2f;
                    canvas.drawText(line, centerX, currentY + businessNameSize, paint);
                    currentY += businessNameSize;
                }
                currentY += sectionSpacing;
            }


            float lineFSpacing = 10f; // pixels between lines
            float lineHHeight = footerSize + lineFSpacing;


            if (receiptJson.getBusinessDetails() != null) {

                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(paymentSecondHeaderSize);

                String address = receiptJson.getBusinessDetails().getAddress() != null ?
                        receiptJson.getBusinessDetails().getAddress() : "";
                String contactNumber = receiptJson.getBusinessDetails().getContactNumber() != null ?
                        receiptJson.getBusinessDetails().getContactNumber() : "";

                if (!address.isEmpty()) {
                    String[] addressLines = address.split(",");
                    currentY += lineHHeight;
                    for (int i = 0; i < addressLines.length; i += 2) {
                        String line1 = addressLines[i].trim();
                        String line2 = (i + 1 < addressLines.length) ? addressLines[i + 1].trim() : "";
                        String combinedLine = line1 + (line2.isEmpty() ? "" : " , " + line2);
                        canvas.drawText(combinedLine.trim(), width / 2, currentY, paint);

                        // move Y for next line
                        currentY += lineHHeight;
                    }
                }

                if (!contactNumber.isEmpty()) {
                    canvas.drawText(contactNumber, width / 2, currentY, paint);
                    currentY += footerSize;
                }

                String website = receiptJson.getBusinessDetails().getWebsite() != null ?
                        receiptJson.getBusinessDetails().getWebsite() : "";
                if (!website.isEmpty()) {
                    canvas.drawText(website, width / 2, currentY, paint);
                    currentY += footerSize;
                }

                String email = receiptJson.getBusinessDetails().getEmail() != null ?
                        receiptJson.getBusinessDetails().getEmail() : "";
                if (!email.isEmpty()) {
                    canvas.drawText(email, width / 2, currentY, paint);
                    currentY += footerSize;
                }
                currentY += footerSize;
            }

            String orderTypeGroup = receiptJson.getOrderTypeGroup();
            String orderTable = receiptJson.getTableName();
            if (orderTypeGroup != null && !orderTypeGroup.trim().isEmpty()) {
                String oTypeGroup = orderTypeGroup;
                if (orderTable != null && !orderTable.trim().isEmpty()) {
                    oTypeGroup = orderTypeGroup + " - " + orderTable;
                }
                paint.setTypeface(mediumFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(orderNoSize); // Use same size as order number for visual balance
                canvas.drawText(oTypeGroup.toUpperCase(), width / 2, currentY + orderNoSize, paint);
                currentY += orderNoSize;
                currentY += sectionSpacing; // More space after order type
            }

            String kotNo = receiptJson.getKotNo();
            String showKotNo = receiptJson.getShowKotNumber();
            if (kotNo != null && !kotNo.trim().isEmpty() && showKotNo != null && !showKotNo.toString().isEmpty() && "true".equalsIgnoreCase(showKotNo.toString())) {
                paint.setTypeface(mediumFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(orderNoSize); // Use same size as order number for visual balance
                canvas.drawText("Ticket #" + kotNo, width / 2, currentY + orderNoSize, paint);
                currentY += orderNoSize;
                currentY += sectionSpacing * 1.5f; // More space after order type
            }


            // --- Order Details ---
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(orderNoSize);
            paint.setTypeface(mediumFont);
            paint.setLetterSpacing(0.1f);

            String orderNo = receiptJson.getOrderNo() != null ? "#" + receiptJson.getOrderNo() : "#";
            List<ReceiptPojo.Total> totals = receiptJson.getTotals();
            //canvas.drawText(orderNo, padding, currentY + headerSize, paint);

            // --- Order Information ---
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(headerSize);
            paint.setTypeface(regularFont);
            paint.setLetterSpacing(0.0f);
            // paymentStatus is null for offline / cash orders and for voided
            // transactions. Now that this transaction-receipt layout is used for
            // EVERY order it can no longer assume a card payment status — fall
            // back to the order's own date/time.
            String orderedTime;
            if (receiptJson.getPaymentStatus() != null && receiptJson.getPaymentStatus().getCreatedTime() != null) {
                orderedTime = receiptJson.getPaymentStatus().getCreatedTime();
            } else {
                orderedTime = ((receiptJson.getOrderDate() != null ? receiptJson.getOrderDate() : "")
                        + (receiptJson.getOrderTime() != null ? (" " + receiptJson.getOrderTime()) : "")).trim();
            }
            //String orderedTime = receiptJson.getEtaTime() != null ? receiptJson.getEtaTime() : "";
            String orderNoVal = "";
            String showReceiptNo = receiptJson.getShowReceiptNo();
            if (receiptJson.getOrderNo() != null && !receiptJson.getOrderNo().trim().isEmpty() && showReceiptNo != null && !showReceiptNo.toString().isEmpty() && "true".equalsIgnoreCase(showReceiptNo.toString())) {
                orderNoVal = receiptJson.getOrderNo() != null ? "Order #" + receiptJson.getOrderNo() : "";
            }
            String printedTime = (receiptJson.getOrderDate() != null ? receiptJson.getOrderDate() : "") + (receiptJson.getOrderTime() != null ? (" " + receiptJson.getOrderTime()) : "");
            String staffName = receiptJson.getServerStaffName() != null ? receiptJson.getServerStaffName() : "";
            float leftX = padding;
            float rightX = width - padding;
            float infoY = currentY + headerSize;

            paint.setTypeface(regularFont);
            paint.setTextSize(headerSize);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(orderedTime, leftX, infoY, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(orderNoVal, rightX, infoY, paint);
            currentY += headerSize * lineSpacingMultiplier;
            // Determine currency symbol
            String currencySymbol = "";
            if (receiptJson.getBusinessDetails() != null && receiptJson.getBusinessDetails().getCountry() != null) {
                if (receiptJson.getBusinessDetails().getCountry().equalsIgnoreCase("US")) {
                    currencySymbol = "$";
                } else if (receiptJson.getBusinessDetails().getCountry().equalsIgnoreCase("IN")) {
                    currencySymbol = "₹";
                }
            }


            // Dotted line below totals
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(3);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
            lineY = currentY + dashSize / 2;
            canvas.drawLine(0, lineY, width, lineY, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            currentY += tableHeaderSize;


            paint.setTypeface(mediumFont);
            paint.setTextSize(grandTotalSize);
            String grandTotalValue = "0.00";
            // T.Receipt TOTAL override -- user spec 2026-09-04: on a
            // TRANSACTION receipt the TOTAL row must mirror the SAME
            // transaction's amount the "Paid by" line below draws
            // (receiptJson.getTransactions().get(0).getAmountTendered(), used
            // a few lines down), not the order-level Grand Total the totals[]
            // JSON still carries when this slip is scoped to one split /
            // transaction (a $100 split-by-amount cash payment on a $200
            // order printed "TOTAL $200.00" next to "Paid by CASH $100.00").
            // Reading it straight from the transaction -- the same field the
            // Paid-by line already uses -- guarantees the two rows match even
            // on an older JS bundle that didn't apply the JS-side override in
            // optimizeReceiptData-utils.ts.
            //
            // Gated on isTransactionReceipt: this renderer also draws the
            // plain EOD-tip slip (isEodTipEnabled || isTransactionReceipt at
            // printReceiptFromJson:3294), and that one is a WHOLE-ORDER
            // receipt whose TOTAL must stay the real grand total.
            boolean isTransactionReceiptSlip = receiptJson.getEodTipConfig() != null
                    && receiptJson.getEodTipConfig().isTransactionReceipt();
            Double scopedPaidAmount = null;
            if (isTransactionReceiptSlip) {
                // Pick the SAME field the Paid line below will draw: the
                // Payment Info block prints "Paid: <paymentStatus.amountTendered>"
                // when paymentStatus.response exists, otherwise the fallback
                // prints "Paid by <tenderType> <transactions[0].amountTendered>".
                if (receiptJson.getPaymentStatus() != null
                        && receiptJson.getPaymentStatus().getResponse() != null) {
                    scopedPaidAmount = receiptJson.getPaymentStatus().getAmountTendered();
                }
                if (scopedPaidAmount == null
                        && receiptJson.getTransactions() != null
                        && !receiptJson.getTransactions().isEmpty()
                        && receiptJson.getTransactions().get(0) != null) {
                    scopedPaidAmount = receiptJson.getTransactions().get(0).getAmountTendered();
                }
            }
            if (scopedPaidAmount != null) {
                grandTotalValue = String.format("%.2f", scopedPaidAmount);
            } else if (totals != null) {
                for (ReceiptPojo.Total total : totals) {
                    if (total == null) continue;
                    String title = total.getTitle() != null ? total.getTitle() : "";
                    if (title.equalsIgnoreCase("Grand Total") || title.equalsIgnoreCase("TOTAL")) {
                        grandTotalValue = total.getValue() != null ?
                                String.format("%.2f", Double.parseDouble(total.getValue())) : "0.00";
                        break;
                    }
                }
            }
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("TOTAL", padding, currentY + grandTotalSize, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setLetterSpacing(0.1f);
            // Add currency symbol before grand total value
            canvas.drawText(currencySymbol + grandTotalValue, width - padding, currentY + grandTotalSize, paint);
            currentY += grandTotalSize + sectionSpacing;

            float keyX = padding;
            float valueX = width - padding;
            float payInfoLineY = currentY + paymentValueSize;
            float lineSpacing = paymentValueSize * lineSpacingMultiplier;

            // --- Payment Info ---
            String paymentLink = receiptJson.getPaymentlink() != null ? receiptJson.getPaymentlink() : "";
            String orderSourceName = receiptJson.getOrderSourceName() != null ? receiptJson.getOrderSourceName() : "";
            if (!paymentLink.isEmpty() && orderSourceName.isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(headerSize);
                paint.setLetterSpacing(0.0f);
                canvas.drawText("Scan To Pay", width / 2, currentY + headerSize, paint);
                currentY += headerSize;

                Bitmap qr = generateQr(paymentLink);
                int qrSize = (int)(width * 0.4f);
                Bitmap scaledQr = Bitmap.createScaledBitmap(qr, qrSize, qrSize, true);
                canvas.drawBitmap(scaledQr, (width - qrSize) / 2, currentY, paint);
                currentY += qrSize + sectionSpacing / 2;

                if (scaledQr != null && !scaledQr.isRecycled()) {
                    scaledQr.recycle();
                }
            } // Print Payment Info if available
            if (receiptJson.getPaymentStatus() != null && receiptJson.getPaymentStatus().getResponse() != null ) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(paymentHeaderSize);
                paint.setLetterSpacing(0.1f);
                List<String> paymentHeaderLines = wrapTextForCenter("Payment Info", paint, width - 2 * padding);
                canvas.drawText("Payment Info", width / 2, currentY + paymentHeaderSize, paint);
                currentY += paymentHeaderSize * lineSpacingMultiplier;
                currentY += paymentHeaderSize;
                payInfoLineY = currentY;

                paint.setTypeface(mediumFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(paymentValueSize);
                paint.setLetterSpacing(0.0f);

                // Refund-aware, same as the other image payment blocks
                // (:4349, :4468) -- this one was the odd one out.
                String paidKey = isRefundStatusCode(receiptJson.getPaymentStatus().getStatusCode())
                        ? "Refund:" : "Paid:";
                String paidValue = currencySymbol + receiptJson.getPaymentStatus().getAmountTendered();
                String tipKey =  "Tip:";
                String tipValue = currencySymbol + receiptJson.getPaymentStatus().getResponse().getTipAmount();
                boolean isAppliedTip = receiptJson.getPaymentStatus() != null && receiptJson.getPaymentStatus().getResponse() != null && receiptJson.getPaymentStatus().getResponse().getTipAmount() != null && receiptJson.getPaymentStatus().getResponse().getTipAmount() != null && Double.parseDouble(receiptJson.getPaymentStatus().getResponse().getTipAmount()) > 0;

                String mIdKey = "Merchant Id ";
                String mIdValue = receiptJson.getPaymentStatus().getResponse().getMerchid() != null ? "XXXXXX" + receiptJson.getPaymentStatus().getResponse().getMerchid().substring(receiptJson.getPaymentStatus().getResponse().getMerchid().length() - 4):"" ;
                String maskedCardKey = "Card ";
                String cardNum = receiptJson.getPaymentStatus().getResponse().getToken();
                String last4Digit = cardNum != null && cardNum.length() >= 4 ? cardNum.substring(cardNum.length() - 4) : "";
                String maskedCardValue = receiptJson.getCardType() + " " + receiptJson.getCardInfo() +  " "+ (cardNum != null && cardNum.length() >= 4 ? "XX" + last4Digit : "");
                String pIdKey = "Payment Id ";
                String pIdValue = receiptJson.getPaymentStatus().getResponse().getPayApiId() != null ? "XX" + receiptJson.getPaymentStatus().getResponse().getPayApiId().substring( receiptJson.getPaymentStatus().getResponse().getPayApiId().length() - 8):
                        receiptJson.getPaymentStatus().getResponse().getPayApi_Id() != null ? "XX" + receiptJson.getPaymentStatus().getResponse().getPayApi_Id().substring( receiptJson.getPaymentStatus().getResponse().getPayApi_Id().length() - 8):"";
                String authRespKey = "Auth response ";
                String authRespValue = receiptJson.getPaymentStatus().getResponse().getResptext() != null ? receiptJson.getPaymentStatus().getResponse().getResptext() : "";
                String authCodeKey = "Auth code ";
                String authCodeValue = receiptJson.getPaymentStatus().getResponse().getAuthcode() != null ? receiptJson.getPaymentStatus().getResponse().getAuthcode() : "";
                String refIdKey = "Ref Id ";
                String refIdValue = receiptJson.getPaymentStatus().getResponse().getRetref();
                String last6DigitRef = refIdValue != null && refIdValue.length() >= 6 ? refIdValue.substring(refIdValue.length() - 6) : "";
                String maskedRef =  (refIdValue != null && refIdValue.length() >= 6 ? "XX" + last6DigitRef : "");


                // Paid
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(paidKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(paidValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                if(isAppliedTip){
                    // Tip
                    paint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(tipKey, keyX, payInfoLineY, paint);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(tipValue, valueX, payInfoLineY, paint);
                    payInfoLineY += lineSpacing;
                }

                // Merchant Id
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(mIdKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(mIdValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                // Card
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(maskedCardKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(maskedCardValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                // Payment Id
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(pIdKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(pIdValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                // Auth Response
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(authRespKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(authRespValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                // Auth Code
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(authCodeKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(authCodeValue, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                // Ref Id
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(refIdKey, keyX, payInfoLineY, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(maskedRef, valueX, payInfoLineY, paint);
                payInfoLineY += lineSpacing;

                currentY = (int) payInfoLineY;
                currentY += sectionSpacing / 2;
            }
            // Fallback: Print simple transaction info if detailed payment status is not available
            else if (receiptJson.getTransactions() != null && !receiptJson.getTransactions().isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(paymentSecondHeaderSize);
                paint.setLetterSpacing(0.0f);
                // Twin of the refund-aware block at :4563, which this one
                // never mirrored.
                String info = (isRefundStatusCode(receiptJson.getTransactions().get(0).getStatusCode())
                        ? "Refund by " : "Paid by ")
                        + receiptJson.getTransactions().get(0).getTenderType() + " " +
                        String.format("%.2f", receiptJson.getTransactions().get(0).getAmountTendered());
                //canvas.drawText(info, padding, currentY + paymentSecondHeaderSize, paint);
                String[] lines = {info};
                for (String line : lines) {
                    // Optionally wrap if line is long
                    List<String> wrapped = wrapTextForCenter(line, paint, width - 2 * padding);
                    for (String wline : wrapped) {
                        canvas.drawText(wline, width / 2, currentY + paymentValueSize, paint);
                        currentY += paymentValueSize * lineSpacingMultiplier;
                    }
                }
                currentY += headerSize + sectionSpacing / 2;
            }

            float lineFHeight = footerSize + lineSpacing;

            // The transaction-receipt layout above is now used for EVERY
            // T.Receipt print (isTransactionReceipt), but the suggested-tip +
            // signature block below only belongs on receipts for merchants
            // that are actually configured for EOD tip. Gate it on
            // isEodTipEnabled, not on isTransactionReceipt.
            boolean isEodTipEnabled = receiptJson.getEodTipConfig() != null && receiptJson.getEodTipConfig().isEodTipEnabled();
            if (isEodTipEnabled) {
            // ---EOD TIP VIEW ----
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(3);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
            lineY = currentY + dashSize / 2;
            canvas.drawLine(0, lineY, width, lineY, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            currentY += orderNoSize;

            paint.setTypeface(mediumFont);
            paint.setLetterSpacing(0.0f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(tableHeaderSize); // Use same size as order number for visual balance
            canvas.drawText("SUGGESTED TIP", width / 2, currentY + orderNoSize, paint);
            currentY += businessNameSize * 1.5;

            List<TipOption> tipOption = receiptJson.getEodTipConfig() != null ? receiptJson.getEodTipConfig().getFixedAmounts() : null;
            String box = "\u2610";
            if (tipOption != null) {
                paint.setTypeface(regularFont);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(tableHeaderValueSize);
                for (TipOption tipData : tipOption) {
                    if (tipData == null) continue;
                    String tipPercent = String.valueOf(tipData.getPercent());
                    String tipAmount = String.format("%.2f", tipData.getTipAmount());
                    String totalAmount = String.format("%.2f", tipData.getTotalAmount());
                    String printedData = box + "  " + tipPercent + "%   (Tip: " + currencySymbol + tipAmount + ",  Total: " + currencySymbol + totalAmount + ")";
                    canvas.drawText(printedData, width / 2, currentY + orderNoSize, paint);
                    currentY += businessNameSize2 * 1.2;
                }
            }
            currentY += 2 * businessNameSize;


            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("............................", keyX, currentY, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("............................", valueX, currentY, paint);
            currentY += lineSpacing;

            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("      CUSTOM TIP", keyX, currentY, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("TOTAL           ", valueX, currentY, paint);
            currentY += lineSpacing;

            currentY += 2 * businessNameSize;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2); // thickness of the line
            paint.setColor(Color.BLACK); // optional: ensure it's visible (default black)
            float lineYY = currentY + (tableHeaderSize / 2f);
            canvas.drawLine(0, lineYY, width, lineYY, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            currentY += tableHeaderSize;

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(mediumFont);
            paint.setTextSize(tableHeaderValueSize);
            canvas.drawText("SIGNATURE", width / 2, currentY + tableHeaderValueSize, paint);
            currentY += 3 * tableHeaderValueSize;


            paint.setTypeface(regularFont);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(paymentValueSize2);
            canvas.drawText("Cardholder agrees to pay as per cardholder agreement", width / 2, currentY + paymentValueSize2, paint);
            currentY += paymentValueSize2;
            currentY += paymentValueSize;
            paint.setTypeface(mediumFont);
            paint.setTextSize(paymentValueSize);
            canvas.drawText("--PLEASE SIGN A COPY FOR THE MERCHANT--", width / 2, currentY + paymentValueSize, paint);
            currentY += paymentValueSize;
            currentY += 3 * tableHeaderValueSize;

            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(3);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 8}, 0));
            lineY = currentY + dashSize / 2;
            canvas.drawLine(0, lineY, width, lineY, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            currentY += orderNoSize;
            }

            // --- Footer ---

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(mediumFont);
            paint.setTextSize(paymentSecondHeaderSize);

            String fullName = receiptJson.getFullName() != null ? receiptJson.getFullName() : "";
            String phone = receiptJson.getPhone() != null ? receiptJson.getPhone() : "";
            if (!fullName.isEmpty() || !phone.isEmpty()) {
                canvas.drawText(fullName, width / 2, currentY + lineFHeight, paint);
                currentY += lineFHeight;
            }
            if(!phone.isEmpty())
            {
                canvas.drawText(phone, width / 2, currentY + lineFHeight, paint);
                currentY += lineFHeight;
            }
            currentY += lineFHeight;

            String reviewQRLink = receiptJson.getReviewQRLink() != null ? receiptJson.getReviewQRLink() : "";
            String reviewMessage = receiptJson.getReviewMessage() != null ? receiptJson.getReviewMessage() : "";
            if (!reviewQRLink.isEmpty() && !reviewMessage.isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(mediumFont);
                paint.setTextSize(headerSize);
                paint.setLetterSpacing(0.0f);
                List<String> reviewText = wrapTextForCenter(reviewMessage, paint, width - 2 * padding);
                for (String wline : reviewText) {
                    canvas.drawText(wline, width / 2, currentY + headerSize, paint);
                    currentY += headerSize * lineSpacingMultiplier;
                }

                Bitmap qr = generateQr(reviewQRLink);
                int qrSize = (int)(width * 0.4f);
                Bitmap scaledQr = Bitmap.createScaledBitmap(qr, qrSize, qrSize, true);
                canvas.drawBitmap(scaledQr, (width - qrSize) / 2, currentY, paint);
                currentY += qrSize + sectionSpacing / 2;

                if (scaledQr != null && !scaledQr.isRecycled()) {
                    scaledQr.recycle();
                }
            }

            if (receiptJson.getFooter() != null && receiptJson.getOrderSourceName() == null) {
                List<String> footerText = wrapTextForCenter(receiptJson.getFooter().getLine1(), paint, width - 2 * padding);
                for (String wline : footerText) {
                    canvas.drawText(wline, width / 2, currentY + lineFHeight, paint);
                    currentY += lineFHeight * lineSpacingMultiplier;
                }
                currentY += lineFHeight;
            }


            // Add extra space after footer
            currentY += sectionSpacing * 4;

            int finalHeight = currentY + padding;
            if (finalHeight > bitmap.getHeight()) {
                finalHeight = bitmap.getHeight(); // Avoid crash
            }

            Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, finalHeight);

            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return croppedBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error generating receipt image: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static void printReceiptTextJson(ReceiptPojo receipt, DeviceConnection printerConnection, boolean is58mm) {
        try {
            EscPosPrinterCommands printerCommands = new EscPosPrinterCommands(printerConnection);
            printerCommands.connect();
            //Thread.sleep(500);

            // Set initial alignment to center
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);

            // Print business details
            if (receipt.getBusinessDetails() != null) {
                String businessName = receipt.getBusinessDetails().getName();
                printerCommands.printText(businessName + "\n\n", EscPosPrinterCommands.TEXT_SIZE_BIG);

                // Print business captions (GSTIN, FSSAI, etc.)
                if (receipt.getBusinessDetails().getCaption() != null && !receipt.getBusinessDetails().getCaption().isEmpty()) {
                    String[] caption = receipt.getBusinessDetails().getCaption().split(",");
                    if (caption[0] != null && !caption[0].isEmpty()) {
                        printerCommands.printText("GSTIN " + caption[0] + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                                EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_COLOR_BLACK, 
                                EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
                    }
                    if (caption[1] != null && !caption[1].isEmpty()) {
                        printerCommands.printText("FSSAI " + caption[1] + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                                EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_COLOR_BLACK, 
                                EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
                    }
                }

                // Print contact number
                if (receipt.getBusinessDetails().getContactNumber() != null && 
                    !receipt.getBusinessDetails().getContactNumber().isEmpty()) {
                    printerCommands.printText("Contact Number " + receipt.getBusinessDetails().getContactNumber() + "\n", 
                            EscPosPrinterCommands.TEXT_SIZE_NORMAL,
                            EscPosPrinterCommands.TEXT_COLOR_BLACK, EscPosPrinterCommands.TEXT_COLOR_BLACK, 
                            EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
                }
            }

            // Print order details
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_LEFT);
            String orderNo = receipt.getOrderNo() != null ? "#" + receipt.getOrderNo() : "#";
            if (receipt.getOrderSourceName() != null && !receipt.getOrderSourceName().isEmpty()) {
                orderNo += " - " + receipt.getOrderSourceName();
            }
            printerCommands.printText("\n" + orderNo + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);

            // Print OTP if available
//            if (receipt.getOTP() != null) {
//                printerCommands.printText("OTP: " + receipt.getOTP() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
//            }

            // Print date and time
            String dateTime = (receipt.getOrderDate() != null ? receipt.getOrderDate() : "") + " " + 
                            (receipt.getOrderTime() != null ? receipt.getOrderTime() : "");
            printerCommands.printText(dateTime + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);

            // Print staff name
            if (receipt.getServerStaffName() != null) {
                printerCommands.printText("Staff - " + receipt.getServerStaffName() + "\n", 
                        EscPosPrinterCommands.TEXT_SIZE_NORMAL);
            }

            // Print table name
            if (receipt.getTableName() != null && !receipt.getTableName().isEmpty()) {
                printerCommands.printText("Table name: " + receipt.getTableName() + "\n", 
                        EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
            }

            // Print customer details
            if (receipt.getFullName() != null || receipt.getPhone() != null) {
                String customerInfo = (receipt.getFullName() != null ? receipt.getFullName() : "") + 
                                    (receipt.getPhone() != null ? "   " + receipt.getPhone() : "");
                printerCommands.printText(customerInfo + "\n", EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
            }

            // Print payment type
            if (receipt.getPaymentType() != null && !receipt.getPaymentType().isEmpty()) {
                printerCommands.printText(receipt.getPaymentType() + "\n", EscPosPrinterCommands.TEXT_WEIGHT_BOLD);
            }

            // Print dashed line
            int maxer = is58mm ? 32 : 46;
            String dashedLine = String.valueOf(new char[maxer]).replace("\0", "-");
            printerCommands.printText(dashedLine + "\n");

            // Print items table
            if (receipt.getItems() != null && !receipt.getItems().isEmpty()) {
                String column = "ITEM;QTY;TOTAL";
                Table table = new Table(column, ";", is58mm ? new int[]{16, 8, 8} : new int[]{30, 10, 6});
                table.addRow(dashedLine);

                for (ReceiptPojo.ReceiptItem item : receipt.getItems()) {
                    if (item == null) continue;

                    String itemName = item.getItemName() != null ? item.getItemName() : "";
                    String qty = item.getQuantity() != null ? item.getQuantity() : "0";
                    String total = item.getSubTotal() != null ? 
                            String.format("%.2f", Double.parseDouble(item.getSubTotal())) : "0.00";

                    // Add item row
                    table.addRow(itemName + ";" + qty + ";" + total);

                    // Add options if any
                    if (item.getOptions() != null) {
                        for (ReceiptPojo.ItemOption option : item.getOptions()) {
                            if (option == null) continue;
                            String optionName = "   " + (option.getOptionName() != null ? option.getOptionName() : "");
                            String optionQty = resolveOptionQty(option, qty);
                            // Fallback stays the unmultiplied unit price this path has always
                            // printed; actualPrice is the extended amount when the BE sends it.
                            String optionTotal = option.getActualPrice() != null
                                    ? String.format("%.2f", option.getActualPrice())
                                    : (option.getPrice() != null
                                            ? String.format("%.2f", Double.parseDouble(option.getPrice()))
                                            : "0.00");
                            table.addRow(optionName + ";" + optionQty + ";" + optionTotal);
                        }
                    }
                }
                table.addRow(dashedLine);
                printerCommands.printText(table.getTableText());
            }

            // Print totals
            if (receipt.getTotals() != null) {
                String totalValue = "";
                for (ReceiptPojo.Total total : receipt.getTotals()) {
                    if (total == null) continue;
                    
                    String title = total.getTitle() != null ? total.getTitle() : "";
                    if (!title.equalsIgnoreCase("Grand Total") && !title.equalsIgnoreCase("TOTAL")) {
                        String value = isPlainNumericValue(total.getValue())
                                ? String.format("%.2f", Double.parseDouble(total.getValue()))
                                : (total.getValue() != null ? total.getValue().trim() : "0.00");
                        int valueLength = value.length();
                        if (value.length() < 6) {
                            valueLength = (6 - value.length()) + valueLength;
                        }
                        // Title may span multiple lines (Discount label + offer names).
                        // Print leading lines as-is, align the amount on the last line,
                        // and clamp padding to avoid a negative array size crash.
                        String[] titleLines = title.split("\n");
                        for (int li = 0; li < titleLines.length - 1; li++) {
                            printerCommands.printText(titleLines[li] + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                        }
                        String lastTitle = titleLines[titleLines.length - 1];
                        int padCount = maxer - (lastTitle.length() + valueLength);
                        if (padCount < 0) padCount = 0;
                        String subLine = lastTitle + String.valueOf(new char[padCount])
                                .replace("\0", " ") + value;
                        printerCommands.printText(subLine + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                    } else {
                        totalValue = total.getValue() != null ? 
                                String.format("%.2f", Double.parseDouble(total.getValue())) : "0.00";
                    }
                }

                printerCommands.printText(dashedLine + "\n");

                // Print grand total
                String total = "TOTAL";
                String totalLine = total + String.valueOf(new char[16 - (total.length() + totalValue.length())])
                        .replace("\0", " ") + totalValue;
                if (!is58mm) {
                    totalLine = total + String.valueOf(new char[23 - (total.length() + totalValue.length())])
                            .replace("\0", " ") + totalValue;
                }
                printerCommands.printText(totalLine + "\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
            }

            // Print payment info
            printerCommands.setAlign(EscPosPrinterCommands.TEXT_ALIGN_CENTER);
            // Pay-QR (generatePayQr): unpaid-order pay link, straight after
            // TOTAL per the Figma. Empty for paid/cancelled/sale or flag off.
            if (receipt.getPayQrLink() != null && !receipt.getPayQrLink().isEmpty()) {
                printerCommands.printText(dashedLine + "\n");
                // UX rev 27 Jul. Pipe separators (plain ASCII) — bullet
                // U+2022 is unreliable in thermal printer codepages.
                printerCommands.printText("\nScan QR Code to Pay\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                printerCommands.printText("Fast | Secure | Contactless\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                Bitmap payQr = generateQr(receipt.getPayQrLink());
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(payQr));
                // Payment-methods strip (MHB-33608): accepted-card logos under
                // the QR. Composited to a single bitmap so it prints as one
                // image row. Null (nothing printed) when no known logo.
                Bitmap payPmStrip = buildPaymentMethodsStrip(
                        mContext, receipt.getCards(), 44, is58mm ? 384 : 560);
                if (payPmStrip != null) {
                    printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(payPmStrip));
                    if (!payPmStrip.isRecycled()) payPmStrip.recycle();
                }
                printerCommands.printText(dashedLine + "\n");
            }
            if (receipt.getPaymentlink() != null && !receipt.getPaymentlink().isEmpty() &&
                receipt.getOrderSourceName() == null) {
                printerCommands.printText("\nScan To Pay\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                Bitmap qr = generateQr(receipt.getPaymentlink());
                printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(qr));
            }

            // Print payment status if available
            if (receipt.getPaymentStatus() != null && receipt.getPaymentStatus().getResponse() != null) {
                printerCommands.printText("\nPayment Info\n", EscPosPrinterCommands.TEXT_SIZE_DOUBLE_WIDTH);
                StringBuilder authBuilder = new StringBuilder();
                
                String cardNum = receipt.getPaymentStatus().getResponse().getToken();
                String last4Digit = cardNum != null && cardNum.length() >= 4 ? 
                        cardNum.substring(cardNum.length() - 4) : "";
                String maskedCard =  (cardNum != null && cardNum.length() >= 4 ?
                        "XXXXXXXXXXXX" + last4Digit : "");

                String refNum = receipt.getPaymentStatus().getResponse().getRetref();
                String last6DigitRef = refNum != null && refNum.length() >= 6 ?
                        refNum.substring(refNum.length() - 6) : "";
                String maskedRef =  (refNum != null && refNum.length() >= 6 ?
                        "XX" + last6DigitRef : "");

                String countryCD = receipt.getPaymentStatus().getRequest() != null && 
                        receipt.getPaymentStatus().getRequest().getPaymentParties() != null && 
                        !receipt.getPaymentStatus().getRequest().getPaymentParties().isEmpty() ?
                        receipt.getPaymentStatus().getRequest().getPaymentParties().get(0).getPaymentCurrency() : "";

                authBuilder.append("Paid: " + receipt.getPaymentStatus().getAmountTendered() + " " + countryCD + "\n");
                authBuilder.append(maskedCard + "\n");
                authBuilder.append("Merchant Id: " + receipt.getPaymentStatus().getResponse().getMerchid() + "\n");
                authBuilder.append("Payment Id: " + receipt.getPaymentStatus().getResponse().getPayApiId() + "\n");
                authBuilder.append("Auth response: " + receipt.getPaymentStatus().getResponse().getResptext() + "\n");
                authBuilder.append("Auth code: " + receipt.getPaymentStatus().getResponse().getAuthcode() + "\n");
                authBuilder.append("Ref Id: " + maskedRef + "\n");
                printerCommands.printText(authBuilder.toString() + "\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
            }

            // Print footer
            if (receipt.getFooter() != null && receipt.getOrderSourceName() == null) {
                printerCommands.printText("\n" + receipt.getFooter().getLine1() + "\n", 
                        EscPosPrinterCommands.TEXT_SIZE_NORMAL);
            }

            // Print business address
            if (receipt.getBusinessDetails() != null) {
                printerCommands.printText(dashedLine + "\n");
                if (receipt.getBusinessDetails().getAddress() != null && 
                    !receipt.getBusinessDetails().getAddress().isEmpty()) {
                    printerCommands.printText(receipt.getBusinessDetails().getAddress() + "\n", 
                            EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                }
                if (receipt.getBusinessDetails().getWebsite() != null && 
                    !receipt.getBusinessDetails().getWebsite().isEmpty()) {
                    printerCommands.printText(receipt.getBusinessDetails().getWebsite() + "\n", 
                            EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                }
                if (receipt.getBusinessDetails().getEmail() != null && 
                    !receipt.getBusinessDetails().getEmail().isEmpty()) {
                    printerCommands.printText(receipt.getBusinessDetails().getEmail() + "\n\n\n", 
                            EscPosPrinterCommands.TEXT_SIZE_NORMAL);
                }
            }

            // Feed paper and cut
           // Thread.sleep(1000);
            printerCommands.printText("\n\n\n", EscPosPrinterCommands.TEXT_SIZE_NORMAL);
            //printerCommands.feedPaper(3);
            printerCommands.cutPaper();
           // Thread.sleep(1000);

        } catch (EscPosConnectionException | EscPosEncodingException  e) {
            e.printStackTrace();
        }
    }

}
