// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ItemReport {

    @SerializedName("totalItemCount")
    @Expose
    int totalItemCount;

    @SerializedName("subTotal")
    @Expose
    String subTotal;

    @SerializedName("categoryName")
    @Expose
    String categoryName;

    @SerializedName("itemReports")
    @Expose
    List<ReportItems> reportItems;


    public int getTotalItemCount() {
        return totalItemCount;
    }

    public String getSubTotal() {
        return subTotal;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public List<ReportItems> getReportItems() {
        return reportItems;
    }
}
