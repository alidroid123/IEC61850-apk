package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;

import com.alidev.dfrtools.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns files handed to the app - "Open with" from another app, or picked from storage - into
 * COMTRADE records: copies content URIs into app storage, unpacks .zip/.rar archives (and the
 * archives inside them, e.g. a .rar of vendor .zip records) and pairs every .cfg with the .dat
 * beside it. Shared by DfrViewerActivity and FaultLocatorActivity.
 */
public final class ComtradeImport {

    /** Cap on what one archive, nested archives included, may unpack to. A DFR record is a few MB. */
    static final long MAX_UNPACKED_BYTES = 512L * 1024 * 1024;
    private static final int MAX_NESTING = 2;
    private static final String[] COMPANION_EXTS = {".hdr", ".inf", ".xml"};

    /** An archive would unpack to more than MAX_UNPACKED_BYTES. */
    static class TooLargeException extends IOException {
        TooLargeException() { super("Archive is too large"); }
    }

    /** A .cfg with its .dat (same folder, same base name) and any .hdr/.inf/.xml beside them. */
    public static class Record {
        public final File cfg, dat;
        public final List<File> companions = new ArrayList<>();
        /** "station, device" from the first .cfg line - tells the two line ends apart in a list. */
        public final String station;

        Record(File cfg, File dat) {
            this.cfg = cfg;
            this.dat = dat;
            String base = baseName(cfg.getName());
            File[] siblings = cfg.getParentFile() != null ? cfg.getParentFile().listFiles() : null;
            if (siblings != null) {
                for (String ext : COMPANION_EXTS) {
                    for (File f : siblings) if (f.getName().equalsIgnoreCase(base + ext)) companions.add(f);
                }
            }
            station = firstCfgLine(cfg);
        }

        /** Path of the .cfg below `root` (just its name when it isn't below root). */
        public String relativeName(File root) {
            String r = root.getAbsolutePath() + File.separator;
            String p = cfg.getAbsolutePath();
            return p.startsWith(r) ? p.substring(r.length()) : cfg.getName();
        }
    }

    private ComtradeImport() { }

    static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    public static boolean isArchive(String name) {
        String n = lower(name);
        return n.endsWith(".zip") || n.endsWith(".rar");
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The name the providing app gave `uri`, or its last path segment. */
    public static String displayName(Context c, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = c.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && cursor.getString(idx) != null) return cursor.getString(idx);
                }
            } catch (Exception ignored) { }
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) return "file";
        return seg.substring(seg.lastIndexOf('/') + 1);
    }

    /** `name` made safe as a single file/folder name. */
    static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "file" : s;
    }

    /** Copies what `uri` points at into the file `out`. */
    public static void copy(Context c, Uri uri, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (InputStream is = c.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (is == null) throw new IOException("Cannot read " + uri);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /**
     * Copies the archive behind `uri` (named `name`) into app storage, unpacks it into
     * <app files>/DFR/Imported/<archive name> - replacing an earlier import of the same name - and
     * returns the records in it. That folder outlives the cache, so the viewer can reopen its last
     * record later, and FL Double Ended's local-record list picks the records up.
     */
    public static List<Record> importArchive(Context c, Uri uri, String name) throws IOException {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File dir = new File(new File(new File(base, "DFR"), "Imported"), safeName(baseName(name)));
        deleteRecursively(dir);
        File copy = new File(c.getCacheDir(), "import_" + System.nanoTime() + (lower(name).endsWith(".rar") ? ".rar" : ".zip"));
        try {
            copy(c, uri, copy);
            return findRecords(unpack(copy, dir));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    /**
     * Unpacks a .zip/.rar into `outDir`, keeping its folders, and returns the files in it.
     * Archives inside it are unpacked in place (into a folder named after them) and removed.
     */
    public static List<File> unpack(File archive, File outDir) throws IOException {
        return unpack(archive, outDir, new long[]{MAX_UNPACKED_BYTES}, 0);
    }

    private static List<File> unpack(File archive, File outDir, long[] budget, int depth) throws IOException {
        List<File> extracted;
        if (lower(archive.getName()).endsWith(".rar")) {
            try {
                extracted = RarExtractor.extract(archive, outDir, budget[0]);
            } catch (RarExtractor.RarException e) {
                if (e.code == RarExtractor.ERR_TOO_LARGE) throw new TooLargeException();
                throw e;
            }
            for (File f : extracted) budget[0] -= f.length();
        } else {
            long before = budget[0];
            try {
                extracted = unzip(archive, outDir, budget, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // Entry names that aren't UTF-8 (older Windows zippers use the OEM code page).
                budget[0] = before;
                extracted = unzip(archive, outDir, budget, StandardCharsets.ISO_8859_1);
            }
        }
        List<File> files = new ArrayList<>();
        for (File f : extracted) {
            if (depth < MAX_NESTING && isArchive(f.getName())) {
                try {
                    List<File> inner = unpack(f, new File(f.getParentFile(), baseName(f.getName())), budget, depth + 1);
                    if (!inner.isEmpty()) {
                        files.addAll(inner);
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        continue;
                    }
                } catch (TooLargeException e) {
                    throw e;
                } catch (IOException ignored) {
                    // Not a readable archive after all - keep it as a plain file.
                }
            }
            files.add(f);
        }
        return files;
    }

    private static List<File> unzip(File zip, File outDir, long[] budget, Charset names) throws IOException {
        List<File> files = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip), names)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File out = safeChild(outDir, e.getName());
                if (out == null) continue;
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        budget[0] -= n;
                        if (budget[0] < 0) throw new TooLargeException();
                        os.write(buf, 0, n);
                    }
                }
                files.add(out);
            }
        }
        return files;
    }

    /** `outDir` + the entry's path with empty, "." and ".." parts dropped (zip-slip safe). */
    private static File safeChild(File outDir, String entryName) {
        File f = outDir;
        boolean any = false;
        for (String part : entryName.split("[/\\\\]")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            f = new File(f, part.replaceAll("\\p{Cntrl}", "_"));
            any = true;
        }
        return any ? f : null;
    }

    /** Every .cfg among `files` that has a .dat beside it, in the given order. */
    public static List<Record> findRecords(List<File> files) {
        List<Record> records = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File f : files) {
            if (!lower(f.getName()).endsWith(".cfg") || !seen.add(f.getAbsolutePath())) continue;
            File dat = ComtradeRecord.findDatFor(f);
            if (dat != null) records.add(new Record(f, dat));
        }
        return records;
    }

    /** The record `file` (a .cfg or a .dat) belongs to, if its partner is in the same folder. */
    public static Record pairFor(File file) {
        String n = lower(file.getName());
        if (n.endsWith(".cfg")) {
            File dat = ComtradeRecord.findDatFor(file);
            return dat != null ? new Record(file, dat) : null;
        }
        File[] siblings = n.endsWith(".dat") && file.getParentFile() != null ? file.getParentFile().listFiles() : null;
        if (siblings == null) return null;
        String cfgName = baseName(file.getName()) + ".cfg";
        for (File f : siblings) if (f.getName().equalsIgnoreCase(cfgName)) return new Record(f, file);
        return null;
    }

    // ------------------------------------------------------------------
    // Partner of a .cfg/.dat picked on its own
    // ------------------------------------------------------------------

    /**
     * The .dat (for a .cfg) or .cfg (for a .dat) that sits next to the document `uri` names,
     * copied into `intoDir` - pickers and "Open with" only hand over the one file that was
     * tapped. Tries the real file behind the URI first (needs the app's storage access), then the
     * provider's own document with the other extension. Null when it can't be reached.
     */
    @Nullable
    static File fetchPartner(Context c, Uri uri, String name, File intoDir) {
        String lower = lower(name);
        String ext = lower.endsWith(".cfg") ? "dat" : lower.endsWith(".dat") ? "cfg" : null;
        if (ext == null) return null;
        String base = baseName(name);
        File local = localFileFor(c, uri, name);
        if (local != null) {
            File partner = findSibling(local.getParentFile(), base, ext);
            if (partner != null) {
                File out = new File(intoDir, safeName(partner.getName()));
                try {
                    copyFile(partner, out);
                    return out;
                } catch (IOException ignored) { }
            }
        }
        for (String e : new String[]{"." + ext, "." + ext.toUpperCase(Locale.ROOT)}) {
            Uri candidate = siblingDocumentUri(c, uri, e);
            if (candidate == null) continue;
            File out = new File(intoDir, safeName(base + e));
            try {
                copy(c, candidate, out);
                return out;
            } catch (Exception ignored) {
                //noinspection ResultOfMethodCallIgnored
                out.delete(); // no grant for the sibling, or it doesn't exist
            }
        }
        return null;
    }

    /** `base`.`ext` in `dir`, any extension case. */
    @Nullable
    static File findSibling(@Nullable File dir, String base, String ext) {
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase(base + "." + ext)) return f;
            return null;
        }
        // Listing not allowed, but the file itself may still be readable.
        for (String e : new String[]{ext.toLowerCase(Locale.ROOT), ext.toUpperCase(Locale.ROOT)}) {
            File f = new File(dir, base + "." + e);
            if (f.canRead()) return f;
        }
        return null;
    }

    /**
     * The file behind a picker / file-manager URI, when its provider reveals the path: file://,
     * the system storage and Downloads providers, MediaStore's _data column, or a path-shaped
     * content URI (most file managers' FileProviders). Only returned if it exists and is named
     * `name`.
     */
    @Nullable
    static File localFileFor(Context c, Uri uri, String name) {
        List<File> candidates = new ArrayList<>();
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) candidates.add(new File(uri.getPath()));
        try {
            if (DocumentsContract.isDocumentUri(c, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                String auth = uri.getAuthority();
                if ("com.android.externalstorage.documents".equals(auth) && id.contains(":")) {
                    String volume = id.substring(0, id.indexOf(':')), rel = id.substring(id.indexOf(':') + 1);
                    File root = "primary".equalsIgnoreCase(volume) ? Environment.getExternalStorageDirectory() : new File("/storage/" + volume);
                    candidates.add(new File(root, rel));
                } else if ("com.android.providers.downloads.documents".equals(auth)) {
                    if (id.startsWith("raw:")) candidates.add(new File(id.substring(4)));
                    else if (id.startsWith("msf:")) addDataColumn(c, android.content.ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"), Long.parseLong(id.substring(4))), candidates);
                }
            }
        } catch (Exception ignored) { }
        addDataColumn(c, uri, candidates);
        // content://<file manager>/<root-name>/Download/DFR/x.cfg: try the path's tails under storage.
        List<String> seg = uri.getPathSegments();
        if ("content".equals(uri.getScheme()) && seg != null) {
            for (int i = 0; i < seg.size(); i++) {
                StringBuilder tail = new StringBuilder();
                for (int k = i; k < seg.size(); k++) tail.append('/').append(seg.get(k));
                candidates.add(new File(Environment.getExternalStorageDirectory(), tail.toString()));
                candidates.add(new File(tail.toString()));
            }
        }
        for (File f : candidates) {
            if (f.getName().equalsIgnoreCase(name) && f.isFile()) return f;
        }
        return null;
    }

    private static void addDataColumn(Context c, Uri uri, List<File> out) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) out.add(new File(cur.getString(0)));
        } catch (Exception ignored) { }
    }

    /** `uri` with its .cfg/.dat extension swapped for `ext` (document id or plain path), or null. */
    @Nullable
    private static Uri siblingDocumentUri(Context c, Uri uri, String ext) {
        try {
            if (DocumentsContract.isDocumentUri(c, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                String lower = lower(id);
                if (lower.endsWith(".cfg") || lower.endsWith(".dat")) {
                    return DocumentsContract.buildDocumentUri(uri.getAuthority(), id.substring(0, id.length() - 4) + ext);
                }
                return null;
            }
        } catch (Exception ignored) { }
        String s = uri.toString();
        String lower = lower(s);
        return lower.endsWith(".cfg") || lower.endsWith(".dat") ? Uri.parse(s.substring(0, s.length() - 4) + ext) : null;
    }

    static void copyFile(File from, File to) throws IOException {
        try (InputStream is = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** User-facing text for an import failure. */
    public static String describeError(Context c, Exception e) {
        if (e instanceof TooLargeException) return c.getString(R.string.msg_import_too_large);
        if (e instanceof RarExtractor.RarException) {
            RarExtractor.RarException re = (RarExtractor.RarException) e;
            if (re.isPassword()) return c.getString(R.string.msg_import_rar_password);
            if (re.code == RarExtractor.ERAR_BAD_ARCHIVE || re.code == RarExtractor.ERAR_UNKNOWN_FORMAT
                    || re.code == RarExtractor.ERAR_BAD_DATA || re.code == RarExtractor.ERAR_EOPEN) {
                return c.getString(R.string.msg_import_rar_damaged);
            }
        }
        return c.getString(R.string.msg_import_failed, String.valueOf(e.getMessage()));
    }

    private static String firstCfgLine(File cfg) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(cfg), StandardCharsets.ISO_8859_1))) {
            String line = r.readLine();
            if (line == null) return "";
            if (line.startsWith("ï»¿")) line = line.substring(3); // UTF-8 BOM
            String[] p = line.split(",");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(2, p.length); i++) {
                String s = p[i].trim();
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(s);
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
