// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Response {

    @SerializedName("fee_value")
    @Expose
    private String feeValue;
    @SerializedName("amount")
    @Expose
    private String amount;
    @SerializedName("resptext")
    @Expose
    private String resptext;
    @SerializedName("fee_authcode")
    @Expose
    private String feeAuthcode;
    @SerializedName("commcard")
    @Expose
    private String commcard;
    @SerializedName("cvvresp")
    @Expose
    private String cvvresp;
    @SerializedName("fee_type")
    @Expose
    private String feeType;
    @SerializedName("respcode")
    @Expose
    private String respcode;
    @SerializedName("avsresp")
    @Expose
    private String avsresp;
    @SerializedName("entrymode")
    @Expose
    private String entrymode;
    @SerializedName("merchid")
    @Expose
    private String merchid;
    @SerializedName("tipAmount")
    @Expose
    private String tipAmount;
    @SerializedName("token")
    @Expose
    private String token;
    @SerializedName(value = "authcode", alternate = {"authCode"})
    @Expose
    private String authcode;
    @SerializedName("respproc")
    @Expose
    private String respproc;
    @SerializedName("fee_amount")
    @Expose
    private String feeAmount;
    @SerializedName("bintype")
    @Expose
    private String bintype;
    @SerializedName("fee_format")
    @Expose
    private String feeFormat;
    @SerializedName("cof")
    @Expose
    private String cof;
    @SerializedName("expiry")
    @Expose
    private String expiry;
    @SerializedName("retref")
    @Expose
    private String retref;
    @SerializedName("respstat")
    @Expose
    private String respstat;
    @SerializedName("fee_retref")
    @Expose
    private String feeRetref;
    @SerializedName("account")
    @Expose
    private String account;
    @SerializedName("fee_merchid")
    @Expose
    private String feeMerchid;
    @SerializedName("payApiId")
    @Expose
    private String payApiId;

    @SerializedName("payAPI_Id")
    @Expose
    private String payApi_Id;

    @SerializedName("brand2")
    @Expose
    private String brand2;

    @SerializedName("extendedCardInfo")
    @Expose
    private String extendedCardInfo;

    public String getCardInfo() {
        return extendedCardInfo;
    }

    public String getCardType() {
        return brand2;
    }

    public String getFeeValue() {
        return feeValue;
    }

    public void setFeeValue(String feeValue) {
        this.feeValue = feeValue;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getResptext() {
        return resptext;
    }

    public void setResptext(String resptext) {
        this.resptext = resptext;
    }

    public String getFeeAuthcode() {
        return feeAuthcode;
    }

    public void setFeeAuthcode(String feeAuthcode) {
        this.feeAuthcode = feeAuthcode;
    }

    public String getCommcard() {
        return commcard;
    }

    public void setCommcard(String commcard) {
        this.commcard = commcard;
    }

    public String getCvvresp() {
        return cvvresp;
    }

    public void setCvvresp(String cvvresp) {
        this.cvvresp = cvvresp;
    }

    public String getFeeType() {
        return feeType;
    }

    public void setFeeType(String feeType) {
        this.feeType = feeType;
    }

    public String getRespcode() {
        return respcode;
    }

    public void setRespcode(String respcode) {
        this.respcode = respcode;
    }

    public String getAvsresp() {
        return avsresp;
    }

    public void setAvsresp(String avsresp) {
        this.avsresp = avsresp;
    }

    public String getEntrymode() {
        return entrymode;
    }

    public void setEntrymode(String entrymode) {
        this.entrymode = entrymode;
    }

    public String getMerchid() {
        return merchid;
    }

    public String getTipAmount(){
        return tipAmount;
    }

    public void setMerchid(String merchid) {
        this.merchid = merchid;
    }

    public void setTipAmount(String tipAmount) {
        this.tipAmount = tipAmount;
    }

    public String getAuthcode() {
        return authcode;
    }

    public void setAuthcode(String authcode) {
        this.authcode = authcode;
    }

    public String getRespproc() {
        return respproc;
    }

    public void setRespproc(String respproc) {
        this.respproc = respproc;
    }

    public String getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(String feeAmount) {
        this.feeAmount = feeAmount;
    }

    public String getBintype() {
        return bintype;
    }

    public void setBintype(String bintype) {
        this.bintype = bintype;
    }

    public String getFeeFormat() {
        return feeFormat;
    }

    public void setFeeFormat(String feeFormat) {
        this.feeFormat = feeFormat;
    }

    public String getCof() {
        return cof;
    }

    public void setCof(String cof) {
        this.cof = cof;
    }

    public String getExpiry() {
        return expiry;
    }

    public void setExpiry(String expiry) {
        this.expiry = expiry;
    }

    public String getRetref() {
        return retref;
    }

    public void setRetref(String retref) {
        this.retref = retref;
    }

    public String getRespstat() {
        return respstat;
    }

    public void setRespstat(String respstat) {
        this.respstat = respstat;
    }

    public String getFeeRetref() {
        return feeRetref;
    }

    public void setFeeRetref(String feeRetref) {
        this.feeRetref = feeRetref;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getFeeMerchid() {
        return feeMerchid;
    }

    public void setFeeMerchid(String feeMerchid) {
        this.feeMerchid = feeMerchid;
    }
      public String getToken() {
        return token;
    }
    public void setToken(String token) {
        this.token = token;
    }

    public void setpayApiId(String token) {
        this.payApiId = payApiId;
    }
    public String getPayApiId() {
        return payApiId;
    }
    public void setpayApi_Id(String token) {
        this.payApi_Id = payApi_Id;
    }
    public String getPayApi_Id() {
        return payApi_Id;
    }


}
