// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class Request {
    @SerializedName("cardPaymentRequest")
    @Expose
    private CardPaymentRequest cardPaymentRequest;
    @SerializedName("paymentParties")
    @Expose
    private List<PaymentParty> paymentParties = null;
    @SerializedName("paymentServiceProviderType")
    @Expose
    private String paymentServiceProviderType;

    public CardPaymentRequest getCardPaymentRequest() {
        return cardPaymentRequest;
    }

    public void setCardPaymentRequest(CardPaymentRequest cardPaymentRequest) {
        this.cardPaymentRequest = cardPaymentRequest;
    }

    public List<PaymentParty> getPaymentParties() {
        return paymentParties;
    }

    public void setPaymentParties(List<PaymentParty> paymentParties) {
        this.paymentParties = paymentParties;
    }

    public String getPaymentServiceProviderType() {
        return paymentServiceProviderType;
    }

    public void setPaymentServiceProviderType(String paymentServiceProviderType) {
        this.paymentServiceProviderType = paymentServiceProviderType;
    }
}
