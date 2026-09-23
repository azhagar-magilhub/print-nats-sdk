package com.magilhub.printnats.pipeline;

import com.google.gson.JsonObject;

/**
 * Master-side host hook for relayed orders, called before the SDK prints an order a client device relayed (and
 * before a client's queued relay is printed locally because this device became master). maghilOrder assigns the
 * location's next KOT number here — clients have no KOT counter.
 */
public interface RelayOrderHook {
    /**
     * @param kind  "KOT", "EDIT_KOT" or "RECEIPT"
     * @param order the relayed order (a copy — may be modified and returned)
     * @return the order to print; null (or a thrown exception) prints the original
     */
    JsonObject prepare(String kind, JsonObject order);
}
