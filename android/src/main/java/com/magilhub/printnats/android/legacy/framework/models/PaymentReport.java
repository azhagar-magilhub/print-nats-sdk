// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class PaymentReport {

    @SerializedName(value = "Order Payment Details",
            alternate = {"orderPaymentDetails","order_payment_details"})
    private List<OrderPaymentDetail> orderPaymentDetails;

    @SerializedName(value = "Payment Information",
            alternate = {"paymentInformation","payment_information"})
    private List<TitleValue> paymentInformation;

    public List<OrderPaymentDetail> getOrderPaymentDetails() { return orderPaymentDetails; }
    public List<TitleValue> getPaymentInformation() { return paymentInformation; }


}
