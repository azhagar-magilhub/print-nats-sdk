package com.magilhub.printnats.rules.receipt;

import com.google.gson.JsonObject;
import com.magilhub.printnats.rules.Restaurant;

/**
 * Everything the JS receipt path reads from outside {@code orderDetails}/{@code restaurantDetails}: network
 * calls, Redux state and device facts. The builder itself never does I/O; the host implements these.
 * Every method may throw — the builder treats a throw exactly like the JS {@code catch} (or the JS helper's
 * own internal catch) does. See docs/receipt-port-notes.md "Services".
 */
public interface ReceiptServices {

    /**
     * JS: {@code loyaltyOrderPointReceipt(orderId)} = GET {@code /api/v1/loyalty/orders/{orderId}/point-receipt}
     * (NestAPI). Called for EVERY receipt whose {@code orderId} is truthy, before anything else is decided.
     * Return {@code response.data.data} when {@code response.data.success} is truthy, else null. A throw → null.
     * Populates {@code loyalty_point_receipt} (projected by optimizeReceiptData).
     */
    JsonObject loyaltyOrderPointReceipt(String orderId);

    /**
     * JS: {@code getReceiptPayQrUrl(orderDetails, restaurantDetails)} (features/order/receiptPayQr.ts). Called
     * ONLY when the pay-QR gate passes (unpaid, not refund-scoped, not split, grand total &gt; 0, not
     * cancelled, not a sale order). The helper itself checks {@code uiFeatureFlags.generatePayQr === true},
     * the orderSourceDetail fast path, its cache, and POST /pay-by-link/receipt-link (3 s cap); it never throws
     * and returns '' to skip. Populates {@code payQrLink} and gates {@code cards}.
     */
    String payQrUrl(JsonObject order, Restaurant restaurant);

    /**
     * JS: {@code isDataCapDevice()} (utils/device-utils.ts) — {@code DeviceInfo.getBrand().toLowerCase() === 'pax'},
     * false on error. Decides {@code isTextReceiptPrint}.
     */
    boolean isDataCapDevice();

    /**
     * JS: Redux {@code state.payment.cpSurchargeByOrder[key] || 0}, key = {@code `${orderId}:${splitId || ''}`}
     * (a JS template, so a missing orderId gives "undefined:"). Only read on the non-India-dine-in totals path.
     * A value &gt; 0 adds a "Card Processing Fee" total row unless the order already has one.
     */
    double cardProcessingSurcharge(String key);

    /**
     * JS: {@code Config.REACT_APP_IMAGE_URL || ''} (react-native-config, build-time) — prefix of the LOGO url
     * in {@code businessDetails.logo}.
     */
    String imageBaseUrl();

    /** No network, not a DataCap device, no surcharge, no image base. */
    ReceiptServices NONE = new ReceiptServices() {
        @Override
        public JsonObject loyaltyOrderPointReceipt(String orderId) {
            return null;
        }

        @Override
        public String payQrUrl(JsonObject order, Restaurant restaurant) {
            return "";
        }

        @Override
        public boolean isDataCapDevice() {
            return false;
        }

        @Override
        public double cardProcessingSurcharge(String key) {
            return 0;
        }

        @Override
        public String imageBaseUrl() {
            return "";
        }
    };
}
