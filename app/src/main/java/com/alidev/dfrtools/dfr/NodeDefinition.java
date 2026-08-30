package com.alidev.dfrtools.dfr;

/** One IEC 61850 "d" (description) attribute captured by MMS Explorer's "Get Definition" - see NodeDefinitionManager. */
public class NodeDefinition {
    public String ip;
    public String deviceName; // same header text IEDMonitoring shows for this ip, e.g. "[GI] bay [Bay]"
    public String nodeAddress; // full MMS path, e.g. "System/AlmGGIO1.Alm10.d"
    public String value; // value read from the "d" attribute
    public boolean hasGeneralStatus; // true if a sibling "general" boolean attribute exists at the same DO
    public String generalStatusValue = ""; // that sibling's live value ("true"/"false"), empty if hasGeneralStatus is false

    public NodeDefinition() {}

    public NodeDefinition(String ip, String deviceName, String nodeAddress, String value) {
        this.ip = ip;
        this.deviceName = deviceName;
        this.nodeAddress = nodeAddress;
        this.value = value;
    }
}
