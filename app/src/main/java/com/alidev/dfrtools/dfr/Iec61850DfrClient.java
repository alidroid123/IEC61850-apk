package com.alidev.dfrtools.dfr;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 (Fase 3). Java-side wrapper around the native libiec61850 MMS client
 * (see app/src/main/cpp/dfr_jni.c). Melanjutkan dari v1.
 * v2: added getLogicalDevices()/readDataAttribute() to support
 * ComtradeSmartSearch's vendor detection.
 *
 * IMPORTANT: every method here does blocking network I/O and must be called
 * from a background thread (see DfrDownloadActivity, which runs everything
 * through a single-thread ExecutorService). Calling these from the main/UI
 * thread will freeze the app and can trigger an ANR.
 *
 * Usage:
 *   Iec61850DfrClient client = new Iec61850DfrClient();
 *   if (client.connect("192.168.1.10", 102, 5000)) {
 *       List<DfrFileEntry> files = client.listFiles(null); // null = root directory
 *       client.downloadFile(files.get(0).name, "/local/path/file.cfg");
 *       client.disconnect();
 *   } else {
 *       String why = client.getLastError();
 *   }
 */
public class Iec61850DfrClient {

    static {
        System.loadLibrary("dfr_jni");
    }

    /** Opaque pointer (as jlong) to the native DfrHandle struct. 0 = not connected. */
    private long handle = 0;

    /**
     * Connects to the relay's MMS server (default IEC 61850 port is 102).
     * @param profile 0=Default, 1=ABB/GE Profile, 2=Schneider Profile
     * @return true on success; on false, call getLastError() for the reason.
     */
    public synchronized boolean connect(String host, int port, int timeoutMs, int profile) {
        if (handle != 0) {
            disconnect();
        }
        handle = nativeConnect(host, port, timeoutMs, profile);
        return handle != 0;
    }

    /** Compatibility overload for v1 code. */
    public boolean connect(String host, int port, int timeoutMs) {
        return connect(host, port, timeoutMs, 0);
    }

    public synchronized boolean isConnected() {
        return handle != 0 && nativeIsConnected(handle);
    }

    /**
     * Frees the native handle. Synchronized (like every other method here) so this can never run
     * concurrently with an in-flight read/connect on the same instance from another thread - doing
     * so would free the native IedConnection out from under a thread still using it (use-after-free
     * -> native crash). This is what let IED Monitoring crash when the user backed out of the
     * screen while a background poll was still reading from the relay.
     */
    public synchronized void disconnect() {
        if (handle != 0) {
            nativeDisconnect(handle);
            handle = 0;
        }
    }

    /**
     * Lists the contents of a directory on the relay's file store.
     * @param directory null/empty for the root directory.
     */
    public synchronized List<DfrFileEntry> listFiles(String directory) {
        List<DfrFileEntry> result = new ArrayList<>();
        if (handle == 0) return result;

        String[] raw = nativeListFiles(handle, directory);
        for (String line : raw) {
            if (line != null && !line.isEmpty()) {
                DfrFileEntry entry = DfrFileEntry.parse(line);
                entry.parentDirectory = directory;
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Downloads one file from the relay to a local path via the MMS GetFile service.
     * @param remoteFileName exact name as returned by listFiles() (DfrFileEntry.name)
     * @param localFilePath  full local destination path (parent dir must already exist)
     */
    public synchronized boolean downloadFile(String remoteFileName, String localFilePath) {
        if (handle == 0) return false;
        return nativeDownloadFile(handle, remoteFileName, localFilePath);
    }

    public synchronized String getLastError() {
        if (handle == 0) return "Belum terhubung ke relay";
        return nativeGetLastError(handle);
    }

    /**
     * v2: Returns the server's Logical Device names. ComtradeSmartSearch uses this to find a
     * usable domain for vendor detection instead of assuming a fixed name like "IEDRCD" - relay
     * configurations in the field aren't guaranteed to use the same Logical Device naming.
     */
    public synchronized List<String> getLogicalDevices() {
        List<String> result = new ArrayList<>();
        if (handle == 0) return result;
        String[] raw = nativeGetLogicalDevices(handle);
        for (String s : raw) {
            if (s != null && !s.isEmpty()) result.add(s);
        }
        return result;
    }

    /**
     * v2: Reads one data attribute as a string, e.g. readDataAttribute("IEDRCD/LPHD1.PhyNam", "vendor", "DC")
     * reads "IEDRCD/LPHD1.PhyNam.vendor" under the DC functional constraint.
     * @return the value, or null if the attribute doesn't exist / isn't a string / read failed.
     */
    public synchronized String readDataAttribute(String logicalDevice, String path, String fc) {
        if (handle == 0) return null;
        String objectRef = logicalDevice + "/" + path;
        return nativeReadDataAttribute(handle, objectRef, fc);
    }

    public synchronized List<String> getLogicalDeviceDirectory(String ldName) {
        List<String> result = new ArrayList<>();
        if (handle == 0) return result;
        String[] raw = nativeGetLogicalDeviceDirectory(handle, ldName);
        for (String s : raw) if (s != null && !s.isEmpty()) result.add(s);
        return result;
    }

    public synchronized List<String> getLogicalNodeDirectory(String lnRef) {
        List<String> result = new ArrayList<>();
        if (handle == 0) return result;
        String[] raw = nativeGetLogicalNodeDirectory(handle, lnRef);
        for (String s : raw) if (s != null && !s.isEmpty()) result.add(s);
        return result;
    }

    public synchronized List<String> getDataDirectory(String dataRef) {
        List<String> result = new ArrayList<>();
        if (handle == 0) return result;
        String[] raw = nativeGetDataDirectory(handle, dataRef);
        for (String s : raw) if (s != null && !s.isEmpty()) result.add(s);
        return result;
    }

    public synchronized String readValueFormatted(String objectRef, String fc) {
        if (handle == 0) return null;
        return nativeReadValueFormatted(handle, objectRef, fc);
    }

    /** Functional constraints tried, in order, when the caller doesn't already know which one applies. */
    private static final String[] FC_FALLBACK_ORDER = {"ST", "MX", "DC", "SP", "CF"};

    public static class FcReadResult {
        public final String fc;
        public final String value;
        FcReadResult(String fc, String value) { this.fc = fc; this.value = value; }
    }

    /**
     * Reads objectRef, trying preferredFc first (when given/known from a previous read), then
     * falling back through FC_FALLBACK_ORDER. Callers should cache the returned fc and pass it
     * back in as preferredFc on the next read of the same objectRef to avoid re-probing every FC.
     * @return the read result, or null if no functional constraint yielded a value.
     */
    public FcReadResult readWithFcFallback(String objectRef, String preferredFc) {
        if (preferredFc != null) {
            FcReadResult r = tryReadFc(objectRef, preferredFc);
            if (r != null) return r;
        }
        for (String fc : FC_FALLBACK_ORDER) {
            if (fc.equals(preferredFc)) continue;
            FcReadResult r = tryReadFc(objectRef, fc);
            if (r != null) return r;
        }
        return null;
    }

    private FcReadResult tryReadFc(String objectRef, String fc) {
        String val = readValueFormatted(objectRef, fc);
        if (val == null) return null;
        String[] parts = val.split("\\|");
        String value = parts.length > 1 ? parts[1] : "";
        // The server can respond with a protocol-level success (err == IED_ERROR_OK) whose payload
        // is an MMS_DATA_ACCESS_ERROR (formatted as "Access Error: <code>" in dfr_jni.c) when the
        // object doesn't exist under this particular FC. That's not a real read - treat it as a miss
        // so readWithFcFallback() keeps trying the remaining FCs instead of locking onto the wrong one.
        if (value.startsWith("Access Error")) return null;
        return new FcReadResult(fc, value);
    }

    public synchronized List<String> getLogicalDeviceVariables(String ldName) {
        List<String> result = new ArrayList<>();
        if (handle == 0) return result;
        String[] raw = nativeGetDeviceVariables(handle, ldName);
        for (String s : raw) if (s != null && !s.isEmpty()) result.add(s);
        return result;
    }

    // ------------------------------------------------------------------
    // Native bridge (implemented in app/src/main/cpp/dfr_jni.c)
    // ------------------------------------------------------------------

    private native long nativeConnect(String host, int port, int timeoutMs, int profile);
    private native void nativeDisconnect(long handle);
    private native boolean nativeIsConnected(long handle);
    private native String nativeGetLastError(long handle);
    private native String[] nativeListFiles(long handle, String directory);
    private native boolean nativeDownloadFile(long handle, String remoteFileName, String localFilePath);
    private native String[] nativeGetLogicalDevices(long handle);
    private native String nativeReadDataAttribute(long handle, String objectReference, String fc);
    private native String[] nativeGetLogicalDeviceDirectory(long handle, String ldName);
    private native String[] nativeGetLogicalNodeDirectory(long handle, String lnRef);
    private native String[] nativeGetDataDirectory(long handle, String dataRef);
    private native String nativeReadValueFormatted(long handle, String objectReference, String fc);
    private native String[] nativeGetDeviceVariables(long handle, String ldName);
}
