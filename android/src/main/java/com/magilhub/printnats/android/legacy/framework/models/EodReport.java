// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class EodReport {

    @SerializedName("orderReport")
    @Expose
    OrderReport orderReport;

    @SerializedName("paymentReport")
    @Expose
    PaymentReport paymentReport;

    @SerializedName("header")
    @Expose
    EODHeader eodHeader;


    public OrderReport getOrderReport() {
        return orderReport;
    }

    public void setOrderReport(OrderReport orderReport) {
        this.orderReport = orderReport;
    }

    public PaymentReport getPaymentReport() {
        return paymentReport;
    }

    public void setPaymentReport(PaymentReport paymentReport) {
        this.paymentReport = paymentReport;
    }

    public EODHeader getEodHeader() {
        return eodHeader;
    }
}
