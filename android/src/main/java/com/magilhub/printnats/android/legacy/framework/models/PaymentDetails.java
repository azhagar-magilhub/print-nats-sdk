// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class PaymentDetails {

    @SerializedName("OFFLINE_QR")
    @Expose
    String offlineQR;

    @SerializedName("PAYMENT_LINKS")
    @Expose
    String paymentLinks;

    @SerializedName("ZOMATO_PRO")
    @Expose
    String zomatoPro;

    @SerializedName("POD")
    @Expose
    String pod;

    @SerializedName("CNP")
    @Expose
    String cnp;

    @SerializedName("POS")
    @Expose
    String pos;

    @SerializedName("CASH")
    @Expose
    String cash;

    @SerializedName("CARD")
    @Expose
    String card;

    @SerializedName("SALARY")
    @Expose
    String salary;

    @SerializedName("DUE")
    @Expose
    String due;

    @SerializedName("NC")
    @Expose
    String nc;

    @SerializedName("NON-CHARGEBLE")
    @Expose
    String nonchargeble;

    @SerializedName("cash")
    @Expose
    String cash2;

    @SerializedName("Additional Charges")
    @Expose
    String additionalCharges;

    @SerializedName("Discount")
    @Expose
    String discount;

    @SerializedName("Item Total")
    @Expose
    String itemTotal;

    @SerializedName("Tip")
    @Expose
    String tip;

    @SerializedName("Convenience Fee")
    @Expose
    String convenienceFee;

    @SerializedName("Tax")
    @Expose
    String tax;

    @SerializedName("Grand Total")
    @Expose
    String grandTotal;

    @SerializedName("Service Tax")
    @Expose
    String serviceCharge;

    public String getPod() {
        if (pod != null) {
            return String.format("%.2f", Double.parseDouble(pod));
        }else{
            return pod;
        }
    }



    public String getCnp() {
        if (cnp != null) {
            return String.format("%.2f", Double.parseDouble(cnp));
        }else{
            return cnp;
        }
    }



    public String getPos() {
        if (pos != null) {
            return String.format("%.2f", Double.parseDouble(pos));
        }else{
            return pos;
        }
    }



    public String getCash() {
        if (cash != null) {
            return String.format("%.2f", Double.parseDouble(cash));
        }else{
            return cash;
        }
    }



    public String getAdditionalCharges() {
        if (additionalCharges != null) {
            return String.format("%.2f", Double.parseDouble(additionalCharges));
        }else{
            return additionalCharges;
        }
    }



    public String getDiscount() {
        if (discount != null) {
            return String.format("%.2f", Double.parseDouble(discount));
        }else{
            return discount;
        }
    }



    public String getItemTotal() {
        if (itemTotal != null) {
            return String.format("%.2f", Double.parseDouble(itemTotal));
        }else{
            return itemTotal;
        }
    }



    public String getTip() {
        if (tip != null) {
            return String.format("%.2f", Double.parseDouble(tip));
        }else{
            return tip;
        }
    }



    public String getTax() {
        if (tax != null) {
            return String.format("%.2f", Double.parseDouble(tax));
        }else{
            return tax;
        }
    }



    public String getGrandTotal() {
        if (grandTotal != null) {
            return String.format("%.2f", Double.parseDouble(grandTotal));
        }else{
            return grandTotal;
        }
    }

    public String getOfflineQR() {
        if (offlineQR != null) {
            return String.format("%.2f", Double.parseDouble(offlineQR));
        }else{
            return offlineQR;
        }
    }

    public String getPaymentLinks() {
        if (paymentLinks != null) {
            return String.format("%.2f", Double.parseDouble(paymentLinks));
        }else{
            return paymentLinks;
        }
    }

    public String getZomatoPro() {
        if (zomatoPro != null) {
            return String.format("%.2f", Double.parseDouble(zomatoPro));
        }else{
            return zomatoPro;
        }
    }

    public String getCard() {
        if (card != null) {
            return String.format("%.2f", Double.parseDouble(card));
        }else{
            return card;
        }
    }

    public String getSalary() {
        if (salary != null) {
            return String.format("%.2f", Double.parseDouble(salary));
        }else{
            return salary;
        }
    }

    public String getDue() {
        if (due != null) {
            return String.format("%.2f", Double.parseDouble(due));
        }else{
            return due;
        }
    }

    public String getNc() {
        if (nc != null) {
            return String.format("%.2f", Double.parseDouble(nc));
        }else{
            return nc;
        }
    }

    public String getNonchargeble() {
        if (nonchargeble != null) {
            return String.format("%.2f", Double.parseDouble(nonchargeble));
        }else{
            return nonchargeble;
        }
    }

    public String getCash2() {
        if (cash2 != null) {
            return String.format("%.2f", Double.parseDouble(cash2));
        }else{
            return cash2;
        }
    }

    public String getConvenienceFee() {
        if (convenienceFee != null) {
            return String.format("%.2f", Double.parseDouble(convenienceFee));
        }else{
            return convenienceFee;
        }
    }

    public String getServiceCharge() {
        if (serviceCharge != null) {
            return String.format("%.2f", Double.parseDouble(serviceCharge));
        }else{
            return serviceCharge;
        }
    }
}
