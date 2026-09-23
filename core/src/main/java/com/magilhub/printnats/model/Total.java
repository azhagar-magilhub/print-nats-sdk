package com.magilhub.printnats.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Total{
    @SerializedName("code")
    @Expose
    private String code;
    @SerializedName("id")
    @Expose
    private String id;
    @SerializedName("sortOrder")
    @Expose
    private String sortOrder;
    @SerializedName("title")
    @Expose
    private String title;
    @SerializedName("value")
    @Expose
    private String value;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(String sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
