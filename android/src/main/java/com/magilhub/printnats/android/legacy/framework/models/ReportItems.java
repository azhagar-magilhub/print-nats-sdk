// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class ReportItems {

    @SerializedName("itemId")
    @Expose
    String itemId;

    @SerializedName("itemName")
    @Expose
    String itemName;

    @SerializedName("quantity")
    @Expose
    String quantity;

    @SerializedName("itemCost")
    @Expose
    String itemCost;

    @SerializedName("subTotal")
    @Expose
    String subTotal;

    @SerializedName("categoryName")
    @Expose
    String categoryName;

    public String getItemId() {
        return itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public String getQuantity() {
        return quantity;
    }

    public String getItemCost() {
        return itemCost;
    }

    public String getSubTotal() {
        return subTotal;
    }

    public String getCategoryName() {
        return categoryName;
    }
}
