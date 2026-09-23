// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DeviceConfig  {
    @SerializedName("id ")
    @Expose
    private String id ;


    @SerializedName("connectionType")
    @Expose
    private String connectionType;

    @SerializedName("selectDevice")
    @Expose
    private String selectDevice;

    @SerializedName("printerType")
    @Expose
    private String printerType;

    @SerializedName("paperType")
    @Expose
    private String paperType;

    @SerializedName("devicename")
    @Expose
    private String devicename;

    @SerializedName("ipAdress")
    @Expose
    private String ipAdress;

    public String getIpaddress() {
        return ipAdress;
    }

    public void setIpaddress(String ipaddress) {
        this.ipAdress = ipaddress;
    }

    public String getDevicename() {
        return devicename;
    }

    public void setDevicename(String devicename) {
        this.devicename = devicename;
    }

    public String getPaperType() {
        return paperType;
    }

    public void setPaperType(String paperType) {
        this.paperType = paperType;
    }

    public String getPrinterType() {
        return printerType;
    }

    public void setPrinterType(String printerType) {
        this.printerType = printerType;
    }

    public String getSelectDevice() {
        return selectDevice;
    }

    public void setSelectDevice(String selectDevice) {
        this.selectDevice = selectDevice;
    }

    public String isConnectionType() {
        return connectionType;
    }

    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType;
    }

    public String getConnectionType() {
        return connectionType;
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

}
