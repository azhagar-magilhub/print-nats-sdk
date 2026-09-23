package com.magilhub.printnats.model;

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
