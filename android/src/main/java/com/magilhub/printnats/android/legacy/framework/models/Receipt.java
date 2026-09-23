// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class Receipt {
    @SerializedName("address")
    @Expose
    private Object address;

    @SerializedName("addressId")
    @Expose
    private Object addressId;

    @SerializedName("addressLine1")
    @Expose
    private String addressLine1;

    @SerializedName("addressLine2")
    @Expose
    private String addressLine2;

    @SerializedName("addressLine3")
    @Expose
    private String addressLine3;

    @SerializedName("altPersonName")
    @Expose
    private Object altPersonName;

    @SerializedName("city")
    @Expose
    private Object city;

    @SerializedName("comment")
    @Expose
    private String comment;

    @SerializedName("paymentlink")
    @Expose
    private String paymentlink;

    @SerializedName("reviewQRLink")
    @Expose
    private String reviewQRLink;

    @SerializedName("reviewMessage")
    @Expose
    private String reviewMessage;

    // Pay-link QR for UNPAID receipts (uiFeatureFlags.generatePayQr) —
    // {customerAppBase}/pay/{shortUrlRedisKey}. Distinct from the IN-only
    // UPI `paymentlink` and from `reviewQRLink`. Empty/absent → QR block
    // is not rendered.
    @SerializedName("payQrLink")
    @Expose
    private String payQrLink;

    // Merchant's accepted payment methods (restaurantDetails.cards). Drives
    // the payment-logo strip under the pay-link QR. Empty/absent → no strip.
    @SerializedName("cards")
    @Expose
    private List<String> cards;

    @SerializedName("countryCd")
    @Expose
    private String countryCd;

    @SerializedName("customerId")
    @Expose
    private String customerId;

    @SerializedName("deliveryDate")
    @Expose
    private String deliveryDate;

    @SerializedName("deliveryStaffDetails")
    @Expose
    private List<Object> deliveryStaffDetails = null;

    @SerializedName("deliveryStaffId")
    @Expose
    private Object deliveryStaffId;

    @SerializedName("deliveryTime")
    @Expose
    private String deliveryTime;

    @SerializedName("deviceId")
    @Expose
    private String deviceId;

    @SerializedName("email")
    @Expose
    private String email;

    @SerializedName("etaDate")
    @Expose
    private String etaDate;

    @SerializedName("etaTime")
    @Expose
    private String etaTime;

    @SerializedName("fullName")
    @Expose
    private String fullName;

    // Both added to fix a real bug (STN-296): buildPrintStatusEvent reads these
    // back out of the saved print-job's data blob (PrintEntity.data) via
    // data.optBoolean("reprintKOT", ...) / data.optString("extraData", ...).
    // Before this, printKot() would deserialize the incoming JSON straight into
    // this class via Gson, which silently drops any key it doesn't declare a
    // field for — so despite useFCMNotificationHandler.tsx setting both on the
    // object it sends (stationReprintOrder), they never survived that
    // round-trip, and a dashboard-initiated reprint always published as
    // type="CreateKot" (the default) instead of "ReprintKot". Object (not
    // Boolean/String) to match this class's existing convention for fields
    // whose incoming JSON type isn't guaranteed consistent.
    @SerializedName("reprintKOT")
    @Expose
    private Object reprintKOT;

    @SerializedName("extraData")
    @Expose
    private Object extraData;

    @SerializedName("isAutoAcceptFeatureIsEnabled")
    @Expose
    private Object isAutoAcceptFeatureIsEnabled;

    @SerializedName("isCashPaymentNotification")
    @Expose
    private Object isCashPaymentNotification;

    @SerializedName("isFCMNotification")
    @Expose
    private Object isFCMNotification;

    @SerializedName("isNewBatch")
    @Expose
    private Object isNewBatch;

    @SerializedName("items")
    @Expose
    private List<Item> items = null;

    @SerializedName("refundedItems")
    @Expose
    private List<Item> refundedItems = null;

    @SerializedName("voidedItems")
    @Expose
    private List<Item> voidedItems = null;

    @SerializedName("refundItems")
    @Expose
    private List<Item> refundItems = null;

    @SerializedName("kotItems")
    @Expose
    private List<Object> kotItems = null;

    @SerializedName("locationAddress")
    @Expose
    private Object locationAddress;

    @SerializedName("locationId")
    @Expose
    private String locationId;

    // Not part of the original receipt shape — JS already attaches the
    // triggering FCM/NATS event's messageId onto printOrder before
    // stringifying (useFCMNotificationHandler.tsx, printOrder.messageId)
    // for exactly this purpose. Without a declared field here, Gson drops
    // it silently on any fromJson/toJson round-trip since it doesn't match
    // any property on this class.
    @SerializedName("messageId")
    @Expose
    private String messageId;

    @SerializedName("locationLat")
    @Expose
    private Object locationLat;

    @SerializedName("locationLng")
    @Expose
    private Object locationLng;

    @SerializedName("locationMedia")
    @Expose
    private Object locationMedia;

    @SerializedName("locationName")
    @Expose
    private Object locationName;

    @SerializedName("offerId")
    @Expose
    private Object offerId;

    @SerializedName("orderDate")
    @Expose
    private String orderDate;

    @SerializedName("orderId")
    @Expose
    private String orderId;

    @SerializedName("orderNo")
    @Expose
    private String orderNo;

    @SerializedName("buzzerNo")
    @Expose
    private String buzzerNo;

    @SerializedName("orderSource")
    @Expose
    private String orderSource;

    @SerializedName("orderSourceDetail")
    @Expose
    private Object orderSourceDetail;

    @SerializedName("orderSourceName")
    @Expose
    private String orderSourceName;

    @SerializedName("orderSourceNo")
    @Expose
    private Object orderSourceNo;

    @SerializedName("orderTime")
    @Expose
    private String orderTime;

    @SerializedName("orderTotal")
    @Expose
    private Double orderTotal;

    @SerializedName("orderTypeGroup")
    @Expose
    private String orderTypeGroup;

    @SerializedName("orderTypeId")
    @Expose
    private String orderTypeId;

    @SerializedName("paymentStatus")
    @Expose
    private PaymentStatus paymentStatus;

    @SerializedName("phone")
    @Expose
    private String phone;

    @SerializedName("pickUpDate")
    @Expose
    private String pickUpDate;

    @SerializedName("pickUpTime")
    @Expose
    private String pickUpTime;

    @SerializedName("postalCd")
    @Expose
    private Object postalCd;

    @SerializedName("refundAmount")
    @Expose
    private Object refundAmount;

    @SerializedName("removedItemId")
    @Expose
    private Object removedItemId;

    @SerializedName("removedOptionId")
    @Expose
    private Object removedOptionId;

    @SerializedName("serverStaffId")
    @Expose
    private Object serverStaffId;

    @SerializedName("serverStaffName")
    @Expose
    private Object serverStaffName;

    @SerializedName("sortOrder")
    @Expose
    private String sortOrder;

    @SerializedName("staffId")
    @Expose
    private String staffId;

    @SerializedName("state")
    @Expose
    private Object state;

    @SerializedName("status")
    @Expose
    private String status;

    @SerializedName("tableId")
    @Expose
    private Object tableId;

    @SerializedName("tableName")
    @Expose
    private Object tableName;

    @SerializedName("totalItems")
    @Expose
    private String totalItems;

    @SerializedName("totals")
    @Expose
    private List<Total> totals = null;

    @SerializedName("transactions")
    @Expose
    private List<Transaction> transactions = null;

    @SerializedName("businessDetails")
    BusinessDetails businessDetails;

    @SerializedName("footer")
    Footer footer;

    @SerializedName("isOrderCancelled")
    @Expose
    private boolean isOrderCancelled;

    @SerializedName("isPaymentDone")
    @Expose
    private boolean isPaymentDone;

    @SerializedName("guestCount")
    @Expose
    private String guestCount;

    @SerializedName("otp")
    @Expose
    private String OTP;

    @SerializedName("currentDate")
    @Expose
    private String currentDate;

    @SerializedName("currentTime")
    @Expose
    private String currentTime;
    @SerializedName("paymentType")
    @Expose
    private String paymentType;

    @SerializedName("isAutoPrint")
    @Expose
    private boolean isAutoPrint;

    @SerializedName("openCashDrawer")
    @Expose
    private boolean openCashDrawer;
    
    @SerializedName("printQR")
    @Expose
    private boolean printQR;

    @SerializedName("isFlushDB")
    @Expose
    private boolean isFlushDB;

    @SerializedName("currentFormattedDate")
    @Expose
    private String currentFormattedDate;

    @SerializedName("orderType")
    @Expose
    private String orderType;

    @SerializedName("isScheduled")
    @Expose
    private boolean isScheduled;

    @SerializedName("isEventOrder")
    @Expose
    private boolean isEventOrder;

    @SerializedName("isCustomizationCountRequired")
    @Expose
    private boolean isCustomizationCountRequired;

    @SerializedName("isSalesOrder")
    @Expose
    private boolean isSalesOrder;

    @SerializedName("cashInfo")
    @Expose
    private Object cashInfo;

    @SerializedName("tabName")
    @Expose
    private String tabName;

    @SerializedName("kotFont")
    @Expose
    private String kotFont;

    @SerializedName("kotAlignmenet")
    @Expose
    private String kotAlignmenet;

    @SerializedName("cardInfo")
    @Expose
    private String cardInfo;

    @SerializedName("cardType")
    @Expose
    private String cardType;

    @SerializedName("transactionStatusCode")
    @Expose
    private String transactionStatusCode;

    // Fee-component refunds ("Refunded Fee Comp", activity 109). Flattened by
    // JS (buildRefundedFeeReceipt); null when the order has no fee refund.
    @SerializedName("refundedFee")
    private RefundedFee refundedFee;

    @SerializedName("refundedAmount")
    @Expose
    private String refundedAmount;

    @SerializedName("showPartySize")
    @Expose
    private String showPartySize;

    @SerializedName("showKotNumber")
    @Expose
    private String showKotNumber;

    // Fire/batch-note decision computed once in JS (useNetworkPrintService) for ALL templates.
    // Null/empty on old payloads -> templates fall back to their legacy derivation.
    @SerializedName("showBatchNote")
    @Expose
    private String showBatchNote;

    @SerializedName("showReceiptNo")
    @Expose
    private String showReceiptNo;

    @SerializedName("showStationName")
    @Expose
    private String showStationName;

    @SerializedName("kotNo")
    @Expose
    private String kotNo;

    @SerializedName("showPaymentStatus")
    @Expose
    private String showPaymentStatus;

    @SerializedName("showPrintTime")
    @Expose
    private String showPrintTime;

    @SerializedName("showEtaTime")
    @Expose
    private String showEtaTime;

    @SerializedName("showStaffNameInKOT")
    @Expose
    private String showStaffNameInKOT;

    @SerializedName("kotFontStyle")
    @Expose
    private String kotFontStyle;

    @SerializedName("templateNo")
    @Expose
    private String templateNo;

    @SerializedName("batchNote")
    @Expose
    private String batchNote;

    @SerializedName("loyalty_point_receipt")
    @Expose
    private LoyaltyPointReceipt loyaltyPointReceipt;

    public static class RefundedFeeLine {
        @SerializedName("name")
        private String name;
        @SerializedName("amount")
        private String amount;

        public String getName() { return name; }
        public String getAmount() { return amount; }
    }

    public static class RefundedFee {
        @SerializedName("title")
        private String title;
        @SerializedName("lines")
        private java.util.List<RefundedFeeLine> lines;
        @SerializedName("totalAmount")
        private String totalAmount;
        @SerializedName("reasons")
        private java.util.List<String> reasons;

        public String getTitle() { return title; }
        public java.util.List<RefundedFeeLine> getLines() { return lines; }
        public String getTotalAmount() { return totalAmount; }
        public java.util.List<String> getReasons() { return reasons; }
    }

    public static class LoyaltyPointReceiptTierAtOrder {
        @SerializedName("tier_name")
        @Expose
        private String tierName;

        @SerializedName("description")
        @Expose
        private String description;

        @SerializedName("multiplier")
        @Expose
        private Double multiplier;

        public String getTierName() {
            return tierName;
        }

        public String getDescription() {
            return description;
        }

        public Double getMultiplier() {
            return multiplier;
        }
    }

    public static class LoyaltyPointReceiptProgram {
        @SerializedName("name")
        @Expose
        private String name;

        @SerializedName("is_paused")
        @Expose
        private Boolean isPaused;

        public String getName() {
            return name;
        }

        public Boolean getIsPaused() {
            return isPaused;
        }
    }

    public static class LoyaltyPointReceiptPointsEarned {
        @SerializedName("total_points")
        @Expose
        private Double totalPoints;

        public Double getTotalPoints() {
            return totalPoints;
        }
    }

    public static class LoyaltyPointReceiptPromotion {
        @SerializedName("name")
        @Expose
        private String name;

        @SerializedName("multiplier_value")
        @Expose
        private Double multiplierValue;

        public String getName() {
            return name;
        }

        public Double getMultiplierValue() {
            return multiplierValue;
        }
    }

    public static class LoyaltyPointReceipt {
        @SerializedName("program")
        @Expose
        private LoyaltyPointReceiptProgram program;

        @SerializedName("tier_at_order")
        @Expose
        private LoyaltyPointReceiptTierAtOrder tierAtOrder;

        @SerializedName("points_earned")
        @Expose
        private LoyaltyPointReceiptPointsEarned pointsEarned;

        @SerializedName("balance_after")
        @Expose
        private Double balanceAfter;

        @SerializedName("points_name")
        @Expose
        private String pointsName;

        @SerializedName("promotions_applied")
        @Expose
        private List<LoyaltyPointReceiptPromotion> promotionsApplied;

        public LoyaltyPointReceiptProgram getProgram() {
            return program;
        }

        public LoyaltyPointReceiptTierAtOrder getTierAtOrder() {
            return tierAtOrder;
        }

        public LoyaltyPointReceiptPointsEarned getPointsEarned() {
            return pointsEarned;
        }

        public Double getBalanceAfter() {
            return balanceAfter;
        }

        public String getPointsName() {
            return pointsName;
        }

        public List<LoyaltyPointReceiptPromotion> getPromotionsApplied() {
            return promotionsApplied;
        }
    }

    // MS-1718 (Template 2 / HBB): DB-driven KOT item formatting. "small"|"big" font size and
    // "title"|"upper"|"lower" text case. Empty/absent -> Template 2 defaults (small + title).
    @SerializedName("kotItemFontSize")
    @Expose
    private String kotItemFontSize;

    @SerializedName("kotItemTextCase")
    @Expose
    private String kotItemTextCase;

    @SerializedName("showGrandTotal")
    @Expose
    private String showGrandTotal;

    @SerializedName("showPaymentMethod")
    @Expose
    private String showPaymentMethod;

    @SerializedName("showUpperCaseItemName")
    @Expose
    private String showUpperCaseItemName;

    @SerializedName("isRequiredParallelQueue")
    @Expose
    private String isRequiredParallelQueue;

    public void setIsRequiredParallelQueue(String isRequiredParallelQueue) {
        this.isRequiredParallelQueue = isRequiredParallelQueue;
    }
    public Object getIsRequiredParallelQueue() {
        return isRequiredParallelQueue;
    }

    public void setShowUpperCaseItemName(String showUpperCaseItemName) {
        this.showUpperCaseItemName = showUpperCaseItemName;
    }
    public Object getShowUpperCaseItemName() {
        return showUpperCaseItemName;
    }

    public void setShowGrandTotal(String showGrandTotal) {
        this.showGrandTotal = showGrandTotal;
    }
    public Object getShowGrandTotal() {
        return showGrandTotal;
    }

    public void setShowPaymentMethod(String showPaymentMethod) {
        this.showPaymentMethod = showPaymentMethod;
    }
    public Object getShowPaymentMethod() {
        return showPaymentMethod;
    }

    public Object getAddress() {
        return address;
    }

    public void setAddress(Object address) {
        this.address = address;
    }

    public Object getAddressId() {
        return addressId;
    }

    public void setAddressId(Object addressId) {
        this.addressId = addressId;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getAddressLine3() {
        return addressLine3;
    }

    public void setAddressLine3(String addressLine3) {
        this.addressLine3 = addressLine3;
    }

    public Object getAltPersonName() {
        return altPersonName;
    }

    public void setAltPersonName(Object altPersonName) {
        this.altPersonName = altPersonName;
    }

    public Object getCity() {
        return city;
    }

    public void setCity(Object city) {
        this.city = city;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getCountryCd() {
        return countryCd;
    }

    public void setCountryCd(String countryCd) {
        this.countryCd = countryCd;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(String deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public List<Object> getDeliveryStaffDetails() {
        return deliveryStaffDetails;
    }

    public void setDeliveryStaffDetails(List<Object> deliveryStaffDetails) {
        this.deliveryStaffDetails = deliveryStaffDetails;
    }

    public Object getDeliveryStaffId() {
        return deliveryStaffId;
    }

    public void setDeliveryStaffId(Object deliveryStaffId) {
        this.deliveryStaffId = deliveryStaffId;
    }

    public String getDeliveryTime() {
        return deliveryTime;
    }

    public void setDeliveryTime(String deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEtaDate() {
        return etaDate;
    }

    public void setEtaDate(String etaDate) {
        this.etaDate = etaDate;
    }

    public String getEtaTime() {
        return etaTime;
    }

    public void setEtaTime(String etaTime) {
        this.etaTime = etaTime;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Object getIsAutoAcceptFeatureIsEnabled() {
        return isAutoAcceptFeatureIsEnabled;
    }

    public void setIsAutoAcceptFeatureIsEnabled(Object isAutoAcceptFeatureIsEnabled) {
        this.isAutoAcceptFeatureIsEnabled = isAutoAcceptFeatureIsEnabled;
    }

    public Object getReprintKOT() {
        return reprintKOT;
    }

    public void setReprintKOT(Object reprintKOT) {
        this.reprintKOT = reprintKOT;
    }

    public Object getExtraData() {
        return extraData;
    }

    public void setExtraData(Object extraData) {
        this.extraData = extraData;
    }

    public Object getIsCashPaymentNotification() {
        return isCashPaymentNotification;
    }

    public void setIsCashPaymentNotification(Object isCashPaymentNotification) {
        this.isCashPaymentNotification = isCashPaymentNotification;
    }

    public Object getIsFCMNotification() {
        return isFCMNotification;
    }

    public void setIsFCMNotification(Object isFCMNotification) {
        this.isFCMNotification = isFCMNotification;
    }

    public Object getIsNewBatch() {
        return isNewBatch;
    }

    public void setIsNewBatch(Object isNewBatch) {
        this.isNewBatch = isNewBatch;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public List<Item> getRefundedItems() {
        return refundedItems;
    }

    public void setRefundedItems(List<Item> refundedItems) {
        this.refundedItems = refundedItems;
    }

    public List<Item> getVoidedItems() {
        return voidedItems;
    }

    public void setVoidedItems(List<Item> voidedItems) {
        this.voidedItems = voidedItems;
    }

    public List<Item> getRefundItems() {
        return refundItems;
    }

    public void setRefundItems(List<Item> refundItems) {
        this.refundItems = refundItems;
    }

    public List<Object> getKotItems() {
        return kotItems;
    }

    public void setKotItems(List<Object> kotItems) {
        this.kotItems = kotItems;
    }

    public Object getLocationAddress() {
        return locationAddress;
    }

    public void setLocationAddress(Object locationAddress) {
        this.locationAddress = locationAddress;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Object getLocationLat() {
        return locationLat;
    }

    public void setLocationLat(Object locationLat) {
        this.locationLat = locationLat;
    }

    public Object getLocationLng() {
        return locationLng;
    }

    public void setLocationLng(Object locationLng) {
        this.locationLng = locationLng;
    }

    public Object getLocationMedia() {
        return locationMedia;
    }

    public void setLocationMedia(Object locationMedia) {
        this.locationMedia = locationMedia;
    }

    public Object getLocationName() {
        return locationName;
    }

    public void setLocationName(Object locationName) {
        this.locationName = locationName;
    }

    public Object getOfferId() {
        return offerId;
    }

    public void setOfferId(Object offerId) {
        this.offerId = offerId;
    }

    public String getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(String orderDate) {
        this.orderDate = orderDate;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getBuzzerNo() {
        return buzzerNo;
    }

    public void setBuzzerNo(String buzzerNo) {
        this.buzzerNo = buzzerNo;
    }

    public String getOrderSource() {
        return orderSource;
    }

    public void setOrderSource(String orderSource) {
        this.orderSource = orderSource;
    }

    public Object getOrderSourceDetail() {
        return orderSourceDetail;
    }

    public void setOrderSourceDetail(Object orderSourceDetail) {
        this.orderSourceDetail = orderSourceDetail;
    }

    public String getOrderSourceName() {
        return orderSourceName;
    }

    public void setOrderSourceName(String orderSourceName) {
        this.orderSourceName = orderSourceName;
    }
    public String getGuestCount() {
        return guestCount;
    }
    public void setGuestCount(String guestCount) {
        this.guestCount = guestCount;
    }

    public Object getOrderSourceNo() {
        return orderSourceNo;
    }

    public void setOrderSourceNo(Object orderSourceNo) {
        this.orderSourceNo = orderSourceNo;
    }

    public String getOrderTime() {
        return orderTime;
    }

    public void setOrderTime(String orderTime) {
        this.orderTime = orderTime;
    }

    public Double getOrderTotal() {
        return orderTotal;
    }

    public void setOrderTotal(Double orderTotal) {
        this.orderTotal = orderTotal;
    }

    public String getOrderTypeGroup() {
        return orderTypeGroup;
    }

    public void setOrderTypeGroup(String orderTypeGroup) {
        this.orderTypeGroup = orderTypeGroup;
    }

    public String getOrderTypeId() {
        return orderTypeId;
    }

    public void setOrderTypeId(String orderTypeId) {
        this.orderTypeId = orderTypeId;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPickUpDate() {
        return pickUpDate;
    }

    public void setPickUpDate(String pickUpDate) {
        this.pickUpDate = pickUpDate;
    }

    public String getPickUpTime() {
        return pickUpTime;
    }

    public void setPickUpTime(String pickUpTime) {
        this.pickUpTime = pickUpTime;
    }

    public Object getPostalCd() {
        return postalCd;
    }

    public void setPostalCd(Object postalCd) {
        this.postalCd = postalCd;
    }

    public Object getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(Object refundAmount) {
        this.refundAmount = refundAmount;
    }

    public Object getRemovedItemId() {
        return removedItemId;
    }

    public void setRemovedItemId(Object removedItemId) {
        this.removedItemId = removedItemId;
    }

    public Object getRemovedOptionId() {
        return removedOptionId;
    }

    public void setRemovedOptionId(Object removedOptionId) {
        this.removedOptionId = removedOptionId;
    }

    public Object getServerStaffId() {
        return serverStaffId;
    }

    public void setServerStaffId(Object serverStaffId) {
        this.serverStaffId = serverStaffId;
    }

    public Object getServerStaffName() {
        return serverStaffName;
    }

    public void setServerStaffName(Object serverStaffName) {
        this.serverStaffName = serverStaffName;
    }

    public String getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(String sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getStaffId() {
        return staffId;
    }

    public void setStaffId(String staffId) {
        this.staffId = staffId;
    }

    public Object getState() {
        return state;
    }

    public void setState(Object state) {
        this.state = state;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Object getTableId() {
        return tableId;
    }

    public void setTableId(Object tableId) {
        this.tableId = tableId;
    }

    public Object getTableName() {
        return tableName;
    }

    public void setTableName(Object tableName) {
        this.tableName = tableName;
    }

    public String getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(String totalItems) {
        this.totalItems = totalItems;
    }

    public List<Total> getTotals() {
        return totals;
    }

    public void setTotals(List<Total> totals) {
        this.totals = totals;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public BusinessDetails getBusinessDetails() {
        return businessDetails;
    }

    public void setBusinessDetails(BusinessDetails businessDetails) {
        this.businessDetails = businessDetails;
    }

    public Footer getFooter() {
        return footer;
    }

    public void setFooter(Footer footer) {
        this.footer = footer;
    }
     public boolean isOrderCancelled() {
        return isOrderCancelled;
    }

    public void setOrderCancelled(boolean isOrderCancelled){
        this.isOrderCancelled=isOrderCancelled;
    }

    public boolean getIsSalesOrder() {
        return isSalesOrder;
    }

    public void setIsSalesOrder(boolean isSalesOrder){
        this.isSalesOrder=isSalesOrder;
    }

    public boolean isPaymentDone() {
        return isPaymentDone;
    }

    public void setPaymentDone(boolean paymentDone) {
        isPaymentDone = paymentDone;
    }


    public String getPaymentlink() {
        return paymentlink;
    }

    public String getReviewQRLink() {
        return reviewQRLink;
    }

    public String getReviewMessage() {
        return reviewMessage;
    }

    public String getPayQrLink() {
        return payQrLink;
    }

    public void setPayQrLink(String payQrLink) {
        this.payQrLink = payQrLink;
    }

    public List<String> getCards() {
        return cards;
    }

    public void setCards(List<String> cards) {
        this.cards = cards;
    }

    public void setPaymentlink(String paymentlink) {
        this.paymentlink = paymentlink;
    }
    public String getOTP() {
        return OTP;
    }

    public void setOTP(String OTP) {
        this.OTP = OTP;
    }

    public String getCurrentDate() {
        return currentDate;
    }

    public void setCurrentDate(String currentDate) {
        this.currentDate = currentDate;
    }

    public String getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(String currentTime) {
        this.currentTime = currentTime;
    }

    public boolean getIsAutoPrint() {
        return isAutoPrint;
    }

    public void setIsAutoPrint(boolean isAutoPrint) {
        isAutoPrint = isAutoPrint;
    }

    public  String getPaymentType(){ return  paymentType;}

    public void setPaymentType(String paymentType) {
        this.paymentType = paymentType;
    }
    public boolean openCashDrawer() {
        return openCashDrawer;
    }

    public boolean setOpenCashDrawer(boolean openCashDrawer){
        return this.openCashDrawer=openCashDrawer;
    }

    public boolean printQR() {
        return printQR;
    }

    public void setPrintQR(boolean printQR){
        this.printQR=printQR;
    }

    public boolean isFlushDB() {
        return isFlushDB;
    }

    public void setFlushDB(boolean isFlushDB){
        this.isFlushDB=isFlushDB;
    }

    public String getCurrentFormattedDate() {
        return currentFormattedDate;
    }

    public void setCurrentFormattedDate(String addressLine3) {
        this.currentFormattedDate = currentFormattedDate;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public boolean getIsScheduled() {
        return isScheduled;
    }

    public void setIsScheduled(boolean isScheduled){
        this.isScheduled=isScheduled;
    }

    public boolean getIsEventOrder() {
        return isEventOrder;
    }

    public void setIsEventOrder(boolean isEventOrder){
        this.isEventOrder=isEventOrder;
    }

    public boolean getIsCustomizationCountRequired() {
        return isCustomizationCountRequired;
    }

    public void setIsCustomizationCountRequired(boolean isCustomizationCountRequired){
        this.isCustomizationCountRequired=isCustomizationCountRequired;
    }

    public void setCashInfo(Object cashInfo){
        this.cashInfo = cashInfo;
    }
    public Object getCashInfo(){
       return cashInfo;
    }

    public String getTabName() {
        return tabName;
    }

    public void setTabName(String tabName) {
        this.tabName = tabName;
    }

        public String getKotAlignmenet() {
        return kotAlignmenet;
    }

    public String getKotFont() {
        return kotFont;
    }


    public void setCardInfo(String cardInfo) {
        this.cardInfo = cardInfo;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }
    public void setTransactionStatusCode(String transactionStatusCode) {
        this.transactionStatusCode = transactionStatusCode;
    }

    public String getCardInfo() {
        return cardInfo;
    }

    public String getCardType() {
        return cardType;
    }
    public String getTransactionStatusCode() {
        return transactionStatusCode;
    }

    public void setRefundedAmount(String refundedAmount) {
        this.refundedAmount = refundedAmount;
    }
    public String getRefundedAmount() { return refundedAmount; }
    public RefundedFee getRefundedFee() { return refundedFee; }

    public void setShowPartySize(String showPartySize) {
        this.showPartySize = showPartySize;
    }
    public String getShowPartySize() { return showPartySize; }

    public void setShowKotNumber(String showKotNumber) {
        this.showKotNumber = showKotNumber;
    }
    public String getShowKotNumber() { return showKotNumber; }

    public void setShowBatchNote(String showBatchNote) {
        this.showBatchNote = showBatchNote;
    }
    public String getShowBatchNote() { return showBatchNote; }

    public void setShowStationName(String showStationName) {
        this.showStationName = showStationName;
    }
    public String getShowStationName() { return showStationName; }

    public void setKotNo(String kotNo) {
        this.kotNo = kotNo;
    }
    public String getKotNo() { return kotNo; }

    public void setShowPaymentStatus(String kotNo) {
        this.showPaymentStatus = showPaymentStatus;
    }
    public String getShowPaymentStatus() { return showPaymentStatus; }

    public void setShowPrintTime(String showPrintTime) {
        this.showPrintTime = showPrintTime;
    }
    public String getShowPrintTime() { return showPrintTime; }


    public void setShowEtaTime(String showEtaTime) {
        this.showEtaTime = showEtaTime;
    }
    public String getShowEtaTime() { return showEtaTime; }

    public void setShowStaffNameInKOT(String showEtaTime) {
        this.showStaffNameInKOT = showStaffNameInKOT;
    }
    public String getShowStaffNameInKOT() { return showStaffNameInKOT; }

    public String getShowReceiptNo() { return showReceiptNo; }

    public String getKotFontStyle() { return kotFontStyle; }

    public String getTemplateNo() { return templateNo; }
    public String getBatchNote() { return batchNote; }
    public LoyaltyPointReceipt getLoyaltyPointReceipt() { return loyaltyPointReceipt; }
    public String getKotItemFontSize() { return kotItemFontSize; }
    public String getKotItemTextCase() { return kotItemTextCase; }


}
