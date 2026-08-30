package com.alidev.dfrtools.dfr;

/** One leaf value collected by MMS Explorer's "Full List" - see FullListFetchService. Not persisted. */
public class FullListEntry {
    public final String fullPath;
    public final String value;

    public FullListEntry(String fullPath, String value) {
        this.fullPath = fullPath;
        this.value = value;
    }
}
