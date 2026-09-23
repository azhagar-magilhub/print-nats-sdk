// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class PaymentParty {
    @SerializedName("partyAccountNumber")
    @Expose
    private String partyAccountNumber;

    @SerializedName("paymentAmount")
    @Expose
    private Double paymentAmount;

    @SerializedName("paymentCurrency")
    @Expose
    private String paymentCurrency;

    @SerializedName("partyName")
    @Expose
    private String partyName;

    @SerializedName("partyAddress")
    @Expose
    private String partyAddress;

    @SerializedName("paymentPartyTypes")
    @Expose
    private String paymentPartyTypes;

    @SerializedName("refundFeesFlag")
    @Expose
    private String refundFeesFlag;

    @SerializedName("holdUntil")
    @Expose
    private Integer holdUntil;

    @SerializedName("paymentPartyRate")
    @Expose
    private Integer paymentPartyRate;

    public String getPartyAccountNumber() {
        return partyAccountNumber;
    }

    public void setPartyAccountNumber(String partyAccountNumber) {
        this.partyAccountNumber = partyAccountNumber;
    }

    public Double getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(Double paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public String getPaymentCurrency() {
        return paymentCurrency;
    }

    public void setPaymentCurrency(String paymentCurrency) {
        this.paymentCurrency = paymentCurrency;
    }

    public String getPartyName() {
        return partyName;
    }

    public void setPartyName(String partyName) {
        this.partyName = partyName;
    }

    public String getPartyAddress() {
        return partyAddress;
    }

    public void setPartyAddress(String partyAddress) {
        this.partyAddress = partyAddress;
    }

    public String getPaymentPartyTypes() {
        return paymentPartyTypes;
    }

    public void setPaymentPartyTypes(String paymentPartyTypes) {
        this.paymentPartyTypes = paymentPartyTypes;
    }

    public String getRefundFeesFlag() {
        return refundFeesFlag;
    }

    public void setRefundFeesFlag(String refundFeesFlag) {
        this.refundFeesFlag = refundFeesFlag;
    }

    public Integer getHoldUntil() {
        return holdUntil;
    }

    public void setHoldUntil(Integer holdUntil) {
        this.holdUntil = holdUntil;
    }

    public Integer getPaymentPartyRate() {
        return paymentPartyRate;
    }

    public void setPaymentPartyRate(Integer paymentPartyRate) {
        this.paymentPartyRate = paymentPartyRate;
    }
}
