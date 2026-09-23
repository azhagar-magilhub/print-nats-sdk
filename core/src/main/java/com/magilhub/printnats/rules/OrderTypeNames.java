package com.magilhub.printnats.rules;

/** Port of order-utils.ts getOrderTypeGroupNamePrinter / getOrderTypeGroupNamePrinterV1 (Release-25.1). */
public final class OrderTypeNames {
    private OrderTypeNames() {
    }

    public static String printer(String orderTypeGroup) {
        if (orderTypeGroup == null) return "-";
        switch (orderTypeGroup) {
            case "D": return "Dine In";
            case "O": return "Online Pickup";
            case "I": return "Pickup";
            case "P": return "Phone Order";
            case "S": return "Delivery";
            default: return "-";
        }
    }

    public static String printerV1(String group, String orderSource, boolean kiosk, boolean qsr, boolean event, boolean voice) {
        if (voice) return "Phone Pickup";
        if ("D".equals(group) && kiosk && qsr) return "Kiosk Dine in";
        if ("D".equals(group) && "D".equals(orderSource) && qsr) return "Counter Dine in";
        if ("D".equals(group) && !qsr) return "Dine in";
        if ("P".equals(group) && kiosk) return "Kiosk Pickup";
        if ("P".equals(group) && "O".equals(orderSource)) return event ? "Event Online" : "Online Pickup";
        if ("I".equals(group) && "P".equals(orderSource)) return "Phone Pickup";
        if ("I".equals(group) || "P".equals(group)) return event ? "Event Counter" : "Counter Pickup";
        if ("S".equals(group) && "O".equals(orderSource)) return "Online Delivery";
        return "-";
    }
}
