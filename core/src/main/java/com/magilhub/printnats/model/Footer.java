package com.magilhub.printnats.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Footer {
    @SerializedName("line1")
    @Expose
    private String line1;

    @SerializedName("line2")
    @Expose
    private String line2;

    public String getLine1() {
        return line1;
    }

    public void setLine1(String line1) {
        this.line1 = line1;
    }

    public String getLine2() {
        return line2;
    }

    public void setLine2(String line2) {
        this.line2 = line2;
    }
}
