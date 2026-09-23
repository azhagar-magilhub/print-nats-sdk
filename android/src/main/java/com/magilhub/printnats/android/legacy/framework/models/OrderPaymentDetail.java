// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.SerializedName;

public class OrderPaymentDetail {
    @SerializedName("paymentMethod") private String paymentMethod;
    @SerializedName("value") private String value;

    public String getPaymentMethod() { return paymentMethod; }
    public String getValue() { return value; }
}
