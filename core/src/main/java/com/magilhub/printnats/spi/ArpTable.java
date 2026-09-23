package com.magilhub.printnats.spi;

import java.util.Map;

/** The OS neighbour (ARP) cache: IPv4 address → MAC (lower-case, ':'-separated). Empty when unreadable. */
public interface ArpTable {
    Map<String, String> ipToMac();
}
