// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.SerializedName;

public class TitleValue {
    @SerializedName("title") private String title;
    @SerializedName("value") private String value; // or double if numeric-only

    public String getTitle() { return title; }
    public String getValue() { return value; }
}
