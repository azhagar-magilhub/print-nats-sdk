// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class OrderReport {


    @SerializedName("Success Orders")
    @Expose
    Orders successOrders;

    @SerializedName("Cancelled Orders")
    @Expose
    Orders cancelledOrders;

    @SerializedName("Complimentary Orders")
    @Expose
    Orders complimentaryOrders;

    @SerializedName("Online Orders")
    @Expose
    Orders onlineOrders;


    public Orders getSuccessOrders() {
        return successOrders;
    }

    public void setSuccessOrders(Orders successOrders) {
        this.successOrders = successOrders;
    }

    public Orders getCancelledOrders() {
        return cancelledOrders;
    }

    public void setCancelledOrders(Orders cancelledOrders) {
        this.cancelledOrders = cancelledOrders;
    }

    public Orders getComplimentaryOrders() {
        return complimentaryOrders;
    }

    public void setComplimentaryOrders(Orders complimentaryOrders) {
        this.complimentaryOrders = complimentaryOrders;
    }

    public Orders getOnlineOrders() {
        return onlineOrders;
    }
}
