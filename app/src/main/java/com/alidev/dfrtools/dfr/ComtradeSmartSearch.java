package com.alidev.dfrtools.dfr;

// v1 (Fase 3 continuation). Business logic ported from the user's original
// Windows desktop project "Comtrade_Downloader" (DownloaderLogic.cpp,
// v4.14.0) so the Android app can replicate its per-vendor smart search
// instead of a plain single-folder file list.
//
// This intentionally lives entirely in Java (not native C) so the search
// logic can be iterated on without touching/recompiling dfr_jni.c -
// mirrors how the rest of this app keeps formulas in Calculators.java
// rather than native code.
//
// Ported 1:1 where possible; the one deliberate behavioral difference from
// the original is documented at each point it occurs:
//  1. Vendor detection reads the Logical Device name dynamically via
//     Iec61850DfrClient.getLogicalDevices() instead of the original's
//     hardcoded domain "IEDRCD" - not guaranteed consistent across every
//     relay in the field (confirmed with the user).
//  2. Downloads run sequentially through the single existing MMS
//     connection instead of the original's 2 parallel worker threads each
//     opening their own MmsConnection. Simpler and avoids the risk of
//     hitting a relay's max-concurrent-association limit (some relays cap
//     this quite low) - a deliberate simplification for the Android MVP,
//     not an oversight.

import android.content.Context;
import com.alidev.dfrtools.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ComtradeSmartSearch {

    /** Ported verbatim from KNOWN_COMTRADE_PATHS in DownloaderLogic.cpp. */
    public static final List<String> KNOWN_COMTRADE_PATHS = Collections.unmodifiableList(Arrays.asList(
            "dr", "/dr",
            "COMTRADE", "/COMTRADE",
            "dr_unextracted", "/dr_unextracted",
            "",
            "PROT", "/PROT",
            "REC", "/REC",
            "RECORD", "/RECORD",
            "measurements", "/measurements",
            "HMI/recordings", "/HMI/recordings",
            "disturbance", "/disturbance"
    ));

    /** Ported from the vendor keyword list in ShouldDeepScan(). */
    private static final String[] DEEP_SCAN_VENDOR_KEYWORDS = {
            "NR", "GE", "ALSTOM", "AREVA", "SCHNEIDER", "P44", "SIEMENS"
    };

    public interface Logger {
        void log(String message);
    }

    // ------------------------------------------------------------------
    // Vendor detection (ported from ShouldDeepScan)
    // ------------------------------------------------------------------

    public static class VendorInfo {
        public final String logicalDevice; // null if it couldn't be read at all
        public final String vendorOrModel; // "" if read succeeded but value was blank
        public final boolean deepScan;

        VendorInfo(String logicalDevice, String vendorOrModel, boolean deepScan) {
            this.logicalDevice = logicalDevice;
            this.vendorOrModel = vendorOrModel;
            this.deepScan = deepScan;
        }
    }

    /**
     * Reads LPHD1.PhyNam.vendor (falls back to .model) from the relay's first reported Logical
     * Device, and checks it against vendor keywords known to spread DFR files across multiple
     * folders (requiring a full scan of every KNOWN_COMTRADE_PATHS entry instead of stopping at
     * the first folder that has files).
     */
    public static VendorInfo detectVendor(Context context, Iec61850DfrClient client, Logger logger) {
        List<String> devices = client.getLogicalDevices();
        if (devices.isEmpty()) {
            if (logger != null) logger.log(context.getString(R.string.msg_dl_scan_no_ld));
            return new VendorInfo(null, "", false);
        }

        String ld = devices.get(0);
        String value = client.readDataAttribute(ld, "LPHD1.PhyNam.vendor", "DC");
        if (value == null || value.trim().isEmpty()) {
            value = client.readDataAttribute(ld, "LPHD1.PhyNam.model", "DC");
        }
        if (value == null) value = "";
        value = value.trim();

        String upper = value.toUpperCase(Locale.ROOT);
        boolean deep = false;
        for (String kw : DEEP_SCAN_VENDOR_KEYWORDS) {
            if (upper.contains(kw)) { deep = true; break; }
        }

        if (logger != null) {
            if (value.isEmpty()) {
                logger.log(context.getString(R.string.msg_dl_vendor_not_readable, ld));
            } else {
                String mode = deep ? context.getString(R.string.lbl_dl_mode_deep) : context.getString(R.string.lbl_dl_mode_normal);
                logger.log(context.getString(R.string.msg_dl_vendor_detected, value, ld, mode));
            }
        }

        return new VendorInfo(ld, value, deep);
    }

    // ------------------------------------------------------------------
    // Multi-folder scan (ported from CollectAllFiles + target filtering)
    // ------------------------------------------------------------------

    public static class ScanResult {
        /** Every file seen across all scanned folders - needed for smart-pair resolution later. */
        public final List<DfrFileEntry> allFiles;
        /** .cfg/.zip candidates only, sorted newest-first (Z-A by name), "...h.zip" entries removed. */
        public final List<DfrFileEntry> targetFiles;

        ScanResult(List<DfrFileEntry> allFiles, List<DfrFileEntry> targetFiles) {
            this.allFiles = allFiles;
            this.targetFiles = targetFiles;
        }
    }

    /**
     * Ported from CollectAllFiles(): tries each of KNOWN_COMTRADE_PATHS in order. In normal mode,
     * stops as soon as one folder yields a COMTRADE candidate (fast path). In deep-scan mode
     * (vendor keyword matched), keeps trying every path even after finding files, since those
     * vendors are known to spread records across multiple folders. Also mirrors the target
     * filtering/sorting/h.zip-removal that the original does at the top of DownloadComtrade().
     */
    public static ScanResult scan(Context context, Iec61850DfrClient client, boolean deepScan, Logger logger) {
        List<DfrFileEntry> collected = new ArrayList<>();
        Set<String> visited = new HashSet<>(); // dedupe by lowercase clean filename
        boolean foundAny = false;

        for (String path : KNOWN_COMTRADE_PATHS) {
            if (!deepScan && foundAny) break;

            List<DfrFileEntry> dirFiles = client.listFiles(path);
            if (dirFiles.isEmpty()) continue;

            int countNew = 0;
            boolean foundHere = false;
            for (DfrFileEntry f : dirFiles) {
                String cleanLower = f.cleanName().toLowerCase(Locale.ROOT);
                if (cleanLower.contains(".cfg") || cleanLower.contains(".zip")) {
                    if (visited.add(cleanLower)) {
                        collected.add(f);
                        foundHere = true;
                        foundAny = true;
                        countNew++;
                    }
                } else {
                    collected.add(f);
                }
            }
            if (foundHere && logger != null) {
                String label = path.isEmpty() ? context.getString(R.string.lbl_all_root) : path;
                logger.log(context.getString(R.string.msg_dl_found_files, label, countNew));
            }
        }

        List<DfrFileEntry> targets = new ArrayList<>();
        for (DfrFileEntry f : collected) {
            String lower = f.cleanName().toLowerCase(Locale.ROOT);
            if (lower.contains(".cfg") || lower.contains(".zip")) targets.add(f);
        }

        // Sort Z-A (newest first - relay filenames are typically timestamp/counter based)
        Collections.sort(targets, (a, b) -> b.cleanName().compareTo(a.cleanName()));

        // v4.14.0 bugfix ported: "...h.zip" entries are never an independent download target,
        // only ever downloaded automatically as the pair of their main ".zip". Left in the
        // target list, they'd otherwise sort to the top (since 'h' > '.') and dominate the queue.
        List<DfrFileEntry> cleanTargets = new ArrayList<>();
        for (DfrFileEntry f : targets) {
            String lower = f.cleanName().toLowerCase(Locale.ROOT);
            if (lower.length() > 5 && lower.substring(lower.length() - 5).equals("h.zip")) continue;
            cleanTargets.add(f);
        }

        return new ScanResult(collected, cleanTargets);
    }

    // ------------------------------------------------------------------
    // Bulk / Single selection (ported from downloadMode 0/1 in DownloadComtrade)
    // ------------------------------------------------------------------

    /** Bulk mode: the N most recent target files. targetFiles must already be sorted Z-A. */
    public static List<DfrFileEntry> selectBulk(List<DfrFileEntry> targetFiles, int n) {
        List<DfrFileEntry> queue = new ArrayList<>();
        for (int i = 0; i < n && i < targetFiles.size(); i++) queue.add(targetFiles.get(i));
        return queue;
    }

    /** Single mode: the Nth file (1-based) in the Z-A sorted list, or null if out of range. */
    public static DfrFileEntry selectSingle(List<DfrFileEntry> targetFiles, int n) {
        int idx = n - 1;
        if (idx >= 0 && idx < targetFiles.size()) return targetFiles.get(idx);
        return null;
    }

    // ------------------------------------------------------------------
    // Smart pairing (ported from DownloadFileSet)
    // ------------------------------------------------------------------

    /**
     * Resolves everything that needs to be downloaded for one selected target:
     *  - target.name ends in ".zip": target + its "h" pair (e.g. drec_291.zip -> drec_291h.zip),
     *    if present in allFiles.
     *  - otherwise (a ".cfg" record): target + every other file in allFiles sharing the same
     *    base name (e.g. record001.cfg -> also record001.dat, record001.hdr, ...).
     */
    public static List<DfrFileEntry> resolveFileSet(DfrFileEntry target, List<DfrFileEntry> allFiles) {
        List<DfrFileEntry> result = new ArrayList<>();
        result.add(target);

        String cleanName = target.cleanName();
        int dot = cleanName.lastIndexOf('.');
        String ext = dot >= 0 ? cleanName.substring(dot).toLowerCase(Locale.ROOT) : "";

        if (ext.equals(".zip")) {
            if (cleanName.length() > 4) {
                String hName = cleanName.substring(0, cleanName.length() - 4) + "h"
                        + cleanName.substring(cleanName.length() - 4);
                for (DfrFileEntry f : allFiles) {
                    if (f.cleanName().equalsIgnoreCase(hName)) {
                        result.add(f);
                        break;
                    }
                }
            }
        } else {
            String baseName = dot >= 0 ? cleanName.substring(0, dot) : cleanName;
            for (DfrFileEntry f : allFiles) {
                String fClean = f.cleanName();
                if (fClean.equalsIgnoreCase(cleanName)) continue;
                if (fClean.regionMatches(true, 0, baseName + ".", 0, baseName.length() + 1)) {
                    result.add(f);
                }
            }
        }
        return result;
    }
}
