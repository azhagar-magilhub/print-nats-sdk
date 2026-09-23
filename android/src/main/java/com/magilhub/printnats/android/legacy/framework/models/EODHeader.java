// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class EODHeader {

    @SerializedName("merchantName")
    @Expose
    String merchantName;

    @SerializedName("startTime")
    @Expose
    String startTime;

    @SerializedName("endTime")
    @Expose
    String endTime;

    public String getMerchantName() {
        return merchantName;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getEndTime() {
        return endTime;
    }
}
