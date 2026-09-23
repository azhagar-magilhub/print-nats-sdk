package com.magilhub.printnats.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class Item {
    @SerializedName("comment")
    @Expose
    private String comment;
    @SerializedName("cuisineId")
    @Expose
    private String cuisineId;
    @SerializedName("id")
    @Expose
    private String id;
    @SerializedName("itemAltName")
    @Expose
    private String itemAltName;
    @SerializedName("itemId")
    @Expose
    private String itemId;
    @SerializedName("IsItemModified")
    @Expose
    private Boolean IsItemModified;
    @SerializedName("itemName")
    @Expose
    private String itemName;
    @SerializedName("name")
    @Expose
    private Object name;
    @SerializedName("orderItemId")
    @Expose
    private Object orderItemId;

    @SerializedName("price")
    @Expose
    private Double price;

    @SerializedName("quantity")
    @Expose
    private String quantity;
    @SerializedName("subTotal")
    @Expose
    private String subTotal;
    @SerializedName("taxFees")
    @Expose
    private String taxFees;
    @SerializedName("categoryName")
    @Expose
    private String category;
    @SerializedName("masterKOT")
    @Expose
    private boolean masterKOT;
    @SerializedName("stationKOT")
    @Expose
    private boolean stationKOT;

    @SerializedName("options")
    @Expose
    private List<Option> options;

    @SerializedName("isWeightBased")
    @Expose
    private boolean isWeightBased;

    @SerializedName("priceUnit")
    @Expose
    private String priceUnit;

    @SerializedName("isFreeItem")
    @Expose
    private Boolean isFreeItem;

    @SerializedName("redeemPoint")
    @Expose
    private String redeemPoint;

    public boolean getIsWeightBased() { return isWeightBased; }
    public void setIsWeightBased(boolean isWeightBased) {
        this.isWeightBased = isWeightBased;
    }

    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String priceUnit) {
        this.priceUnit = priceUnit;
    }

    public Boolean getIsFreeItem() {
        return isFreeItem;
    }

    public void setIsFreeItem(Boolean isFreeItem) {
        this.isFreeItem = isFreeItem;
    }

    public String getRedeemPoint() {
        return redeemPoint;
    }

    public void setRedeemPoint(String redeemPoint) {
        this.redeemPoint = redeemPoint;
    }


    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getCuisineId() {
        return cuisineId;
    }

    public void setCuisineId(String cuisineId) {
        this.cuisineId = cuisineId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getItemAltName() {
        return itemAltName;
    }

    public void setItemAltName(String itemAltName) {
        this.itemAltName = itemAltName;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public Boolean getIsItemModified() {
        return IsItemModified;
    }

    public void setIsItemModified(Boolean IsitemModified) {
        this.IsItemModified = IsItemModified;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public Object getName() {
        return name;
    }

    public void setName(Object name) {
        this.name = name;
    }

    public Object getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(Object orderItemId) {
        this.orderItemId = orderItemId;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public String getQuantity() {
        return quantity;
    }

    public void setQuantity(String quantity) {
        this.quantity = quantity;
    }

    public String getSubTotal() {
        return subTotal;
    }

    public void setSubTotal(String subTotal) {
        this.subTotal = subTotal;
    }

    public String getTaxFees() {
        return taxFees;
    }

    public void setTaxFees(String taxFees) {
        this.taxFees = taxFees;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isMasterKOT() {
        return masterKOT;
    }

    public void setMasterKOT(boolean masterKOT) {
        this.masterKOT = masterKOT;
    }

    public boolean isStationKOT() {
        return stationKOT;
    }

    public void setStationKOT(boolean stationKOT) {
        this.stationKOT = stationKOT;
    }

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options;
    }
}
