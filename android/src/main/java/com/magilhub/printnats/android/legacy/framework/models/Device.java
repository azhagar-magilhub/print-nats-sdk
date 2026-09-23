// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

public class Device {
    String name;
    String address;
    String id;
    int type;
    int vendorId;
    int productId;

    public Device(int id,String name, String address,int type,int vendorId,int productId) {
        this.id = String.valueOf(id);
        this.name = name;
        this.address = address;
        this.type = type;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    public int getId() {
        return Integer.parseInt(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getVendorId() {
        return vendorId;
    }

    public void setVendorId(int vendorId) {
        this.vendorId = vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public void setProductId(int productId) {
        this.productId = productId;
    }
}
