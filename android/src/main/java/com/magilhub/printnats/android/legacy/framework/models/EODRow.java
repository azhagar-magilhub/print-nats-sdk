// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

public class EODRow {

    String title,count,total;
    boolean isDashedLine = false;


    public EODRow(String title, String count, String total) {
        this.title = title;
        this.count = count;
        this.total = total;
        isDashedLine = false;
    }

    public EODRow(boolean isDashedLine) {
        this.isDashedLine = isDashedLine;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCount() {
        return count;
    }

    public void setCount(String count) {
        this.count = count;
    }

    public String getTotal() {
        return total;
    }

    public void setTotal(String total) {
        this.total = total;
    }

    public boolean isDashedLine() {
        return isDashedLine;
    }

    public void setDashedLine(boolean dashedLine) {
        isDashedLine = dashedLine;
    }
}
