package com.alidev.dfrtools.dfr;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2. One entry returned by the relay's MMS file directory listing
 * (typically a COMTRADE .cfg/.dat pair, or a containing subdirectory).
 * Melanjutkan dari v1: added parentDirectory + fullPath()/cleanName(),
 * ported from FixDoublePath()/GetCleanFileName() in the user's original
 * Windows Comtrade_Downloader project (DownloaderLogic.cpp). MMS file
 * names returned by the server aren't guaranteed to already include the
 * directory they were listed from, so callers need to carry that
 * separately and re-join it defensively when building the download path.
 */
public class DfrFileEntry {

    public final String name;
    public final long sizeBytes;
    public final long lastModifiedEpochMillis;

    /** Directory this entry was listed from (as passed to listFiles()). Set by Iec61850DfrClient.listFiles(). */
    public String parentDirectory = null;

    public DfrFileEntry(String name, long sizeBytes, long lastModifiedEpochMillis) {
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.lastModifiedEpochMillis = lastModifiedEpochMillis;
    }

    /** Parses the "name|size|epochMillis" triplet produced by dfr_jni.c's nativeListFiles(). */
    public static DfrFileEntry parse(String raw) {
        String[] parts = raw.split("\\|", 3);
        String name = parts.length > 0 ? parts[0] : "";
        long size = 0;
        long modified = 0;
        try { if (parts.length > 1) size = Long.parseLong(parts[1]); } catch (NumberFormatException ignored) { }
        try { if (parts.length > 2) modified = Long.parseLong(parts[2]); } catch (NumberFormatException ignored) { }
        return new DfrFileEntry(name, size, modified);
    }

    public boolean isDirectory() {
        // MMS file services represent directories as entries ending in "/"
        return name.endsWith("/");
    }

    public boolean looksLikeComtrade() {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cfg") || lower.endsWith(".dat") || lower.endsWith(".cff");
    }

    public boolean looksLikeTarget() {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains(".cfg") || lower.contains(".zip");
    }

    /** Just the file name with any directory component stripped (ported from GetCleanFileName). */
    public String cleanName() {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /**
     * Joins parentDirectory + name defensively, avoiding a doubled-up path if `name` already
     * contains the directory (ported verbatim from FixDoublePath in the original C++ project -
     * MMS servers aren't consistent about whether getFileDirectory results already include
     * the queried directory prefix, so this has to tolerate both cases).
     */
    public String fullPath() {
        if (parentDirectory == null || parentDirectory.isEmpty()) return name;
        if (!name.isEmpty() && (name.charAt(0) == '/' || name.charAt(0) == '\\')) return name;

        String cleanDir = parentDirectory;
        char last = cleanDir.charAt(cleanDir.length() - 1);
        if (last == '/' || last == '\\') cleanDir = cleanDir.substring(0, cleanDir.length() - 1);

        if (name.startsWith(cleanDir)) {
            if (name.length() == cleanDir.length()
                    || name.charAt(cleanDir.length()) == '/' || name.charAt(cleanDir.length()) == '\\') {
                return name;
            }
        }
        return cleanDir + "/" + name;
    }

    public String formattedSize() {
        if (sizeBytes <= 0) return "-";
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0);
        return String.format(Locale.getDefault(), "%.2f MB", sizeBytes / (1024.0 * 1024.0));
    }

    public String formattedDate() {
        if (lastModifiedEpochMillis <= 0) return "-";
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault());
        return fmt.format(new Date(lastModifiedEpochMillis));
    }
}
