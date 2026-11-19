package com.mdproject.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;

import com.mdproject.dfrtools.DfrApp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Built-in demo substation pair, so every screen can be tried without a real relay (and so Google
 * Play's reviewers, who have no IEC 61850 relay on their network, can exercise the app). Two
 * simulated relays live at 192.0.2.11 / .12 (RFC 5737 documentation range: never a real host);
 * Iec61850DfrClient answers for them from here instead of opening a socket. The two COMTRADE
 * records under assets/demo are synthetic (an A-G fault 17.5 km from GI DEMO A on a 50 km line).
 */
public final class DemoData {

    public static final String IP_A = "192.0.2.11";
    public static final String IP_B = "192.0.2.12";

    private static final String PREFS = "demo_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String LD = "DemoLD0";
    private static final String LINE_ID = "demo-line-1";

    private DemoData() {}

    public static boolean isDemoIp(String ip) {
        return ip != null && ip.trim().startsWith("192.0.2.");
    }

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    // ------------------------------------------------------------------
    // Install / remove the demo configuration
    // ------------------------------------------------------------------

    /** Adds the two demo relays, their monitored points and the demo line; idempotent. */
    public static void install(Context c) {
        c = c.getApplicationContext();
        SharedPreferences dfr = c.getSharedPreferences("dfr_prefs", Context.MODE_PRIVATE);
        try {
            JSONArray devices = new JSONArray(dfr.getString("device_list", "[]"));
            for (String[] d : new String[][]{
                    {"GI DEMO A", "SALURAN DEMO 1", "RELAY DEMO A", IP_A},
                    {"GI DEMO B", "SALURAN DEMO 1", "RELAY DEMO B", IP_B}}) {
                boolean exists = false;
                for (int i = 0; i < devices.length(); i++) {
                    if (d[3].equals(devices.getJSONObject(i).optString("ip"))) { exists = true; break; }
                }
                if (exists) continue;
                JSONObject o = new JSONObject();
                o.put("upt", "UPT DEMO");
                o.put("ultg", "ULTG DEMO");
                o.put("gi", d[0]);
                o.put("bay", d[1]);
                o.put("device", d[2]);
                o.put("ip", d[3]);
                o.put("merk", "DEMO");
                o.put("type", "SIM-1");
                devices.put(o);
            }
            dfr.edit().putString("device_list", devices.toString()).apply();
        } catch (JSONException ignored) { }

        MonitoringManager mm = new MonitoringManager(c);
        for (String[] ip : new String[][]{{IP_A, "RELAY DEMO A"}, {IP_B, "RELAY DEMO B"}}) {
            mm.addNode(node(ip, "Frekuensi", "MMXU1.Hz.mag.f", "float", "Hz", null));
            mm.addNode(node(ip, "Daya Aktif", "MMXU1.TotW.mag.f", "float", "MW", null));
            mm.addNode(node(ip, "Arus Fasa A", "MMXU1.A.phsA.cVal.mag.f", "float", "A", null));
            mm.addNode(node(ip, "Arus Fasa C", "MMXU1.A.phsC.cVal.mag.f", "float", "A",
                    IP_B.equals(ip[0]) ? Float.valueOf(400f) : null));
            mm.addNode(node(ip, "Tegangan Fasa A", "MMXU1.PhV.phsA.cVal.mag.f", "float", "kV", null));
            MonitoredNode trip = node(ip, "Proteksi Trip", "PTRC1.Tr.general", "boolean", "", null);
            trip.alarmEnabled = true;
            trip.alarmOnValue = true;
            mm.addNode(trip);
            mm.addNode(node(ip, "Posisi CB", "XCBR1.Pos.stVal", "string", "", null));
        }

        LineData l = new LineData();
        l.id = LINE_ID;
        l.no = "DEMO"; l.upt = "UPT DEMO"; l.ultg = "ULTG DEMO";
        l.gi = "GI DEMO A"; l.giUip2b = "GI DEMO A"; l.bay = "SALURAN DEMO 1"; l.giLawan = "GI DEMO B";
        l.grade = "2"; l.merk = "DEMO"; l.tipe = "SIM-1"; l.konduktor = "DEMO 2x400 mm2";
        l.kv = 150; l.lengthKm = 50;
        l.r1 = 0.0699; l.x1 = 0.3912; l.r0 = 0.2; l.x0 = 1.2;
        l.completeDerived();
        new LineDataManager(c).upsert(l);

        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply();
    }

    /** Removes only what install() added. Files downloaded from the demo relays stay in Download/DFR. */
    public static void remove(Context c) {
        c = c.getApplicationContext();
        SharedPreferences dfr = c.getSharedPreferences("dfr_prefs", Context.MODE_PRIVATE);
        try {
            JSONArray devices = new JSONArray(dfr.getString("device_list", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject o = devices.getJSONObject(i);
                if (!isDemoIp(o.optString("ip"))) kept.put(o);
            }
            dfr.edit().putString("device_list", kept.toString()).apply();
        } catch (JSONException ignored) { }
        MonitoringManager mm = new MonitoringManager(c);
        mm.removeNodesForIp(IP_A);
        mm.removeNodesForIp(IP_B);
        new LineDataManager(c).delete(LINE_ID);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
    }

    private static MonitoredNode node(String[] ip, String name, String path, String type, String unit, Float high) {
        MonitoredNode n = new MonitoredNode(ip[1], ip[0], path.substring(path.lastIndexOf('.') + 1),
                LD + "/" + path, type);
        n.customName = name;
        n.unit = unit;
        if (high != null) {
            n.alarmEnabled = true;
            n.thresholdHigh = high;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Simulated relay: file store
    // ------------------------------------------------------------------

    private static String recordBase(String host) {
        return IP_B.equals(host) ? "DEMO_B_0001" : "DEMO_A_0001";
    }

    static List<DfrFileEntry> listFiles(String directory, String host) {
        List<DfrFileEntry> out = new ArrayList<>();
        String dir = directory == null ? "" : directory.replace("/", "");
        if (!dir.equals("dr")) return out;
        long stamp = System.currentTimeMillis() - 3600_000L;
        for (String ext : new String[]{".cfg", ".dat"}) {
            String name = recordBase(host) + ext;
            out.add(new DfrFileEntry(name, assetSize(name), stamp));
        }
        return out;
    }

    static boolean downloadFile(String remoteName, String localPath) {
        String name = remoteName.substring(remoteName.lastIndexOf('/') + 1);
        if (!name.startsWith("DEMO_")) return false;
        try (InputStream in = DfrApp.get().getAssets().open("demo/" + name);
             OutputStream out = new FileOutputStream(new File(localPath))) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            Thread.sleep(150); // gives the progress UI something to show
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static long assetSize(String name) {
        try (InputStream in = DfrApp.get().getAssets().open("demo/" + name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return b.size();
        } catch (IOException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Simulated relay: IEC 61850 data model (MMS-style "LN$FC$DO$DA..." variables)
    // ------------------------------------------------------------------

    /** {variable, kind}: "f" = float measurement, "b" = boolean (false), "e:text" = fixed text. */
    private static final String[][] VARS = {
            {"LLN0$DC$NamPlt$vendor", "e:DEMO"},
            {"LLN0$DC$NamPlt$swRev", "e:1.0-demo"},
            {"LLN0$ST$Mod$stVal", "e:on"},
            {"LLN0$ST$Beh$stVal", "e:on"},
            {"LLN0$ST$Health$stVal", "e:Ok"},
            {"LPHD1$DC$PhyNam$vendor", "e:DEMO"},
            {"LPHD1$DC$PhyNam$model", "e:SIM-1"},
            {"LPHD1$ST$PhyHealth$stVal", "e:Ok"},
            {"MMXU1$MX$Hz$mag$f", "f"},
            {"MMXU1$MX$TotW$mag$f", "f"},
            {"MMXU1$MX$A$phsA$cVal$mag$f", "f"},
            {"MMXU1$MX$A$phsB$cVal$mag$f", "f"},
            {"MMXU1$MX$A$phsC$cVal$mag$f", "f"},
            {"MMXU1$MX$PhV$phsA$cVal$mag$f", "f"},
            {"MMXU1$MX$PhV$phsB$cVal$mag$f", "f"},
            {"MMXU1$MX$PhV$phsC$cVal$mag$f", "f"},
            {"PTRC1$ST$Tr$general", "b"},
            {"PTRC1$ST$Op$general", "b"},
            {"PTRC1$ST$Str$general", "b"},
            {"XCBR1$ST$Pos$stVal", "e:on"},
            {"XCBR1$ST$Loc$stVal", "b"},
    };

    /** One variable as {path under the LD ("MMXU1.A.phsA.cVal.mag.f"), FC, kind}. */
    private static String[] split(String[] v) {
        String[] p = v[0].split("\\$");
        StringBuilder path = new StringBuilder(p[0]);
        for (int i = 2; i < p.length; i++) path.append('.').append(p[i]);
        return new String[]{path.toString(), p[1], v[1]};
    }

    static List<String> logicalDevices() {
        List<String> l = new ArrayList<>();
        l.add(LD);
        return l;
    }

    static List<String> deviceVariables(String ld) {
        List<String> l = new ArrayList<>();
        if (!LD.equals(ld)) return l;
        for (String[] v : VARS) l.add(v[0]);
        return l;
    }

    /** Children of a reference: "LD" -> logical nodes, "LD/LN" -> data objects, deeper -> sub-attributes. */
    static List<String> children(String ref) {
        Set<String> out = new LinkedHashSet<>();
        if (ref == null || !ref.startsWith(LD)) return new ArrayList<>(out);
        String rest = ref.length() > LD.length() ? ref.substring(LD.length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        for (String[] v : VARS) {
            String path = split(v)[0];
            if (rest.isEmpty()) {
                out.add(path.substring(0, path.indexOf('.')));
            } else if (path.startsWith(rest + ".")) {
                String tail = path.substring(rest.length() + 1);
                int dot = tail.indexOf('.');
                out.add(dot < 0 ? tail : tail.substring(0, dot));
            }
        }
        return new ArrayList<>(out);
    }

    /** "FC|value" like the native reader returns, or null when the object/FC doesn't exist. */
    static String readValue(String ref, String fc, String host) {
        if (ref == null || !ref.startsWith(LD + "/")) return null;
        String path = ref.substring(LD.length() + 1);
        for (String[] v : VARS) {
            String[] s = split(v);
            if (s[0].equals(path) && s[1].equals(fc)) return fc + "|" + value(s[0], s[2], host);
        }
        return null;
    }

    private static String value(String path, String kind, String host) {
        if (kind.startsWith("e:")) return kind.substring(2);
        if (kind.equals("b")) return "false";
        double t = System.currentTimeMillis() / 1000.0;
        boolean b = IP_B.equals(host);
        double ph = b ? 1.3 : 0.0;
        double v;
        switch (path) {
            case "MMXU1.Hz.mag.f": v = 50.0 + 0.02 * Math.sin(t / 7 + ph); break;
            case "MMXU1.TotW.mag.f": v = (b ? -38.0 : 40.0) + 1.5 * Math.sin(t / 11 + ph); break;
            case "MMXU1.A.phsA.cVal.mag.f": v = 310 + 12 * Math.sin(t / 9 + ph); break;
            case "MMXU1.A.phsB.cVal.mag.f": v = 305 + 12 * Math.sin(t / 9 + 2.1 + ph); break;
            case "MMXU1.A.phsC.cVal.mag.f": v = (b ? 440 : 300) + 12 * Math.sin(t / 9 + 4.2 + ph); break;
            case "MMXU1.PhV.phsA.cVal.mag.f":
            case "MMXU1.PhV.phsB.cVal.mag.f":
            case "MMXU1.PhV.phsC.cVal.mag.f": v = 86.6 + 0.3 * Math.sin(t / 13 + ph); break;
            default: v = 0;
        }
        return String.format(Locale.US, "%.3f", v);
    }
}
