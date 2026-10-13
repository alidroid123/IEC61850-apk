package com.alidev.dfrtools.dfr;

import android.content.Context;

import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Alignment;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Cx;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.EndSummary;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Phasors;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Result;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Warning;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Saved FL Double Ended analyses: one JSON file per result in the app's private storage, holding
 * everything the result card shows (so a saved analysis reopens exactly as it was, without the
 * COMTRADE records) plus the context it was made in.
 */
public class FlResultStore {

    public static class Saved {
        public String id = "";
        public long savedAt;
        /** When the analysis was run (the PDF footer's timestamp); savedAt for older saves. */
        public long analyzedAt;
        public String nameA = "", nameB = "", lineConfig = "", recordA = "", recordB = "", pdfPath = "";
        public double tauUsed = Double.NaN;
        public boolean syncAsMain;
        public Result result;
    }

    private final File dir;

    public FlResultStore(Context context) {
        dir = new File(context.getFilesDir(), "fl_results");
        if (!dir.exists()) dir.mkdirs();
    }

    public void save(Saved s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("savedAt", s.savedAt);
        o.put("analyzedAt", s.analyzedAt);
        o.put("nameA", s.nameA);
        o.put("nameB", s.nameB);
        o.put("lineConfig", s.lineConfig);
        o.put("recordA", s.recordA);
        o.put("recordB", s.recordB);
        o.put("pdfPath", s.pdfPath);
        num(o, "tauUsed", s.tauUsed);
        o.put("syncAsMain", s.syncAsMain);
        o.put("result", resultToJson(s.result));
        try (FileOutputStream fos = new FileOutputStream(new File(dir, s.id + ".json"))) {
            fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Newest first; unreadable files are skipped. */
    public List<Saved> list() {
        List<Saved> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try (InputStream is = new FileInputStream(f)) {
                JSONObject o = new JSONObject(new String(ComtradeRecord.readAll(is), StandardCharsets.UTF_8));
                Saved s = new Saved();
                s.id = o.optString("id", f.getName().replace(".json", ""));
                s.savedAt = o.optLong("savedAt", f.lastModified());
                s.analyzedAt = o.optLong("analyzedAt", s.savedAt);
                s.nameA = o.optString("nameA", "");
                s.nameB = o.optString("nameB", "");
                s.lineConfig = o.optString("lineConfig", "");
                s.recordA = o.optString("recordA", "");
                s.recordB = o.optString("recordB", "");
                s.pdfPath = o.optString("pdfPath", "");
                s.tauUsed = o.optDouble("tauUsed", Double.NaN);
                s.syncAsMain = o.optBoolean("syncAsMain", false);
                s.result = resultFromJson(o.getJSONObject("result"));
                out.add(s);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, (x, y) -> Long.compare(y.savedAt, x.savedAt));
        return out;
    }

    public void delete(String id) {
        File f = new File(dir, id + ".json");
        if (f.exists()) f.delete();
    }

    // ------------------------------------------------------------------
    // Result <-> JSON
    // ------------------------------------------------------------------

    static JSONObject resultToJson(Result r) throws JSONException {
        JSONObject o = new JSONObject();
        num(o, "m", r.m);
        num(o, "distA", r.distA);
        num(o, "distB", r.distB);
        num(o, "lengthKm", r.lengthKm);
        num(o, "mAlt", r.mAlt);
        o.put("sequenceUsed", r.sequenceUsed);
        o.put("superimposed", r.superimposed);
        o.put("distributedModel", r.distributedModel);
        num(o, "mPos", r.mPos);
        num(o, "mSync", r.mSync);
        o.put("syncTrusted", r.syncTrusted);
        num(o, "takagiA", r.takagiA);
        num(o, "takagiB", r.takagiB);
        o.put("faultType", r.faultType);
        num(o, "syncAngleDeg", r.syncAngleDeg);
        num(o, "inceptionDiffMs", r.inceptionDiffMs);
        num(o, "rf", r.rf);
        o.put("dirA", r.dirA);
        o.put("dirB", r.dirB);
        num(o, "offsetCycles", r.offsetCycles);
        num(o, "instability", r.instability);
        o.put("windowsUsed", r.windowsUsed);
        num(o, "spreadKm", r.spreadKm);
        num(o, "tauAuto", r.tauAuto);
        num(o, "tauInception", r.tauInception);
        num(o, "tauTimestamp", r.tauTimestamp);
        num(o, "tauPre", r.tauPre);
        num(o, "tauVoltage", r.tauVoltage);
        num(o, "syncAngleSpreadDeg", r.syncAngleSpreadDeg);
        num(o, "syncAnglePreDeg", r.syncAnglePreDeg);
        num(o, "z1MeasR", r.z1MeasR);
        num(o, "z1MeasX", r.z1MeasX);
        num(o, "z1MeasUnc", r.z1MeasUnc);
        num(o, "alignRefTimeA", r.alignRefTimeA);
        num(o, "alignRefCurrentA", r.alignRefCurrentA);
        o.put("endA", endToJson(r.endA));
        o.put("endB", endToJson(r.endB));
        o.put("profileKm", arr(r.profileKm));
        o.put("profileA", arr(r.profileA));
        o.put("profileB", arr(r.profileB));
        JSONArray w = new JSONArray();
        for (Warning x : r.warnings) {
            JSONObject wo = new JSONObject();
            wo.put("code", x.code);
            JSONArray args = new JSONArray();
            for (Object a : x.args) {
                if (a instanceof Number) {
                    double d = ((Number) a).doubleValue();
                    args.put(Double.isNaN(d) || Double.isInfinite(d) ? JSONObject.NULL : d);
                } else {
                    args.put(String.valueOf(a));
                }
            }
            wo.put("args", args);
            w.put(wo);
        }
        o.put("warnings", w);
        if (r.alignment != null) o.put("alignment", alignmentToJson(r.alignment));
        return o;
    }

    static Result resultFromJson(JSONObject o) throws JSONException {
        Result r = new Result();
        r.m = o.optDouble("m", Double.NaN);
        r.distA = o.optDouble("distA", Double.NaN);
        r.distB = o.optDouble("distB", Double.NaN);
        r.lengthKm = o.optDouble("lengthKm", Double.NaN);
        r.mAlt = o.optDouble("mAlt", Double.NaN);
        r.sequenceUsed = o.optInt("sequenceUsed", 2);
        r.superimposed = o.optBoolean("superimposed", false);
        r.distributedModel = o.optBoolean("distributedModel", false);
        r.mPos = o.optDouble("mPos", Double.NaN);
        r.mSync = o.optDouble("mSync", Double.NaN);
        r.syncTrusted = o.optBoolean("syncTrusted", false);
        r.takagiA = o.optDouble("takagiA", Double.NaN);
        r.takagiB = o.optDouble("takagiB", Double.NaN);
        r.faultType = o.optString("faultType", "?");
        r.syncAngleDeg = o.optDouble("syncAngleDeg", Double.NaN);
        r.inceptionDiffMs = o.optDouble("inceptionDiffMs", Double.NaN);
        r.rf = o.optDouble("rf", Double.NaN);
        r.dirA = o.optInt("dirA", 0);
        r.dirB = o.optInt("dirB", 0);
        r.offsetCycles = o.optDouble("offsetCycles", Double.NaN);
        r.instability = o.optDouble("instability", Double.NaN);
        r.windowsUsed = o.optInt("windowsUsed", 1);
        r.spreadKm = o.optDouble("spreadKm", Double.NaN);
        r.tauAuto = o.optDouble("tauAuto", Double.NaN);
        r.tauInception = o.optDouble("tauInception", Double.NaN);
        r.tauTimestamp = o.optDouble("tauTimestamp", Double.NaN);
        r.tauPre = o.optDouble("tauPre", Double.NaN);
        r.tauVoltage = o.optDouble("tauVoltage", Double.NaN);
        r.syncAngleSpreadDeg = o.optDouble("syncAngleSpreadDeg", Double.NaN);
        r.syncAnglePreDeg = o.optDouble("syncAnglePreDeg", Double.NaN);
        r.z1MeasR = o.optDouble("z1MeasR", Double.NaN);
        r.z1MeasX = o.optDouble("z1MeasX", Double.NaN);
        r.z1MeasUnc = o.optDouble("z1MeasUnc", Double.NaN);
        r.alignRefTimeA = o.optDouble("alignRefTimeA", Double.NaN);
        r.alignRefCurrentA = o.optDouble("alignRefCurrentA", Double.NaN);
        r.endA = endFromJson(o.optJSONObject("endA"));
        r.endB = endFromJson(o.optJSONObject("endB"));
        r.profileKm = darr(o.optJSONArray("profileKm"));
        r.profileA = darr(o.optJSONArray("profileA"));
        r.profileB = darr(o.optJSONArray("profileB"));
        JSONArray w = o.optJSONArray("warnings");
        if (w != null) {
            for (int i = 0; i < w.length(); i++) {
                JSONObject wo = w.getJSONObject(i);
                JSONArray args = wo.optJSONArray("args");
                Object[] a = new Object[args == null ? 0 : args.length()];
                for (int k = 0; k < a.length; k++) {
                    Object v = args.opt(k);
                    // Warning texts format numbers as Double - JSON may hand back an Integer.
                    a[k] = v instanceof Number ? (Object) ((Number) v).doubleValue()
                            : v == null || v == JSONObject.NULL ? (Object) Double.NaN : v;
                }
                r.warnings.add(new Warning(wo.optString("code"), a));
            }
        }
        JSONObject al = o.optJSONObject("alignment");
        if (al != null) r.alignment = alignmentFromJson(al);
        return r;
    }

    private static JSONObject endToJson(EndSummary e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("station", e.station);
        o.put("triggerText", e.triggerText);
        o.put("fileType", e.fileType);
        num(o, "fs", e.fs);
        num(o, "f0", e.f0);
        o.put("samplesPerCycle", e.samplesPerCycle);
        num(o, "inceptionTime", e.inceptionTime);
        o.put("inceptionSource", e.inceptionSource);
        o.put("hasPrefault", e.hasPrefault);
        num(o, "displayRefAngle", e.displayRefAngle);
        o.put("secondaryWithoutRatio", e.secondaryWithoutRatio);
        if (e.pre != null) o.put("pre", phasorsToJson(e.pre));
        if (e.flt != null) o.put("flt", phasorsToJson(e.flt));
        return o;
    }

    private static EndSummary endFromJson(JSONObject o) throws JSONException {
        EndSummary e = new EndSummary();
        if (o == null) return e;
        e.station = o.optString("station", "");
        e.triggerText = o.optString("triggerText", "");
        e.fileType = o.optString("fileType", "");
        e.fs = o.optDouble("fs", Double.NaN);
        e.f0 = o.optDouble("f0", Double.NaN);
        e.samplesPerCycle = o.optInt("samplesPerCycle", 0);
        e.inceptionTime = o.optDouble("inceptionTime", Double.NaN);
        e.inceptionSource = o.optString("inceptionSource", "T");
        e.hasPrefault = o.optBoolean("hasPrefault", false);
        e.displayRefAngle = o.optDouble("displayRefAngle", 0);
        e.secondaryWithoutRatio = o.optBoolean("secondaryWithoutRatio", false);
        if (o.has("pre")) e.pre = phasorsFromJson(o.getJSONObject("pre"));
        if (o.has("flt")) e.flt = phasorsFromJson(o.getJSONObject("flt"));
        return e;
    }

    private static JSONObject alignmentToJson(Alignment a) throws JSONException {
        JSONObject o = new JSONObject();
        num(o, "tau", a.tau);
        o.put("valid", a.valid);
        if (a.a != null) o.put("a", phasorsToJson(a.a));
        if (a.b != null) o.put("b", phasorsToJson(a.b));
        num(o, "dV1Deg", a.dV1Deg);
        num(o, "dV0Deg", a.dV0Deg);
        num(o, "dV2Deg", a.dV2Deg);
        o.put("v0Used", a.v0Used);
        num(o, "m", a.m);
        return o;
    }

    private static Alignment alignmentFromJson(JSONObject o) throws JSONException {
        Alignment a = new Alignment();
        a.tau = o.optDouble("tau", Double.NaN);
        a.valid = o.optBoolean("valid", false);
        if (o.has("a")) a.a = phasorsFromJson(o.getJSONObject("a"));
        if (o.has("b")) a.b = phasorsFromJson(o.getJSONObject("b"));
        a.dV1Deg = o.optDouble("dV1Deg", Double.NaN);
        a.dV0Deg = o.optDouble("dV0Deg", Double.NaN);
        a.dV2Deg = o.optDouble("dV2Deg", Double.NaN);
        a.v0Used = o.optBoolean("v0Used", false);
        a.m = o.optDouble("m", Double.NaN);
        return a;
    }

    private static JSONObject phasorsToJson(Phasors p) throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray v = new JSONArray(), i = new JSONArray();
        for (int k = 0; k < 3; k++) {
            v.put(cx(p.v[k]));
            i.put(cx(p.i[k]));
        }
        o.put("v", v);
        o.put("i", i);
        return o;
    }

    private static Phasors phasorsFromJson(JSONObject o) throws JSONException {
        Phasors p = new Phasors();
        JSONArray v = o.getJSONArray("v"), i = o.getJSONArray("i");
        for (int k = 0; k < 3; k++) {
            p.v[k] = cx(v.getJSONArray(k));
            p.i[k] = cx(i.getJSONArray(k));
        }
        p.computeSequences();
        return p;
    }

    private static JSONArray cx(Cx c) throws JSONException {
        JSONArray a = new JSONArray();
        a.put(Double.isNaN(c.re) ? 0 : c.re);
        a.put(Double.isNaN(c.im) ? 0 : c.im);
        return a;
    }

    private static Cx cx(JSONArray a) {
        return new Cx(a.optDouble(0, 0), a.optDouble(1, 0));
    }

    private static JSONArray arr(double[] d) throws JSONException {
        JSONArray a = new JSONArray();
        if (d != null) for (double x : d) a.put(Double.isNaN(x) || Double.isInfinite(x) ? 0 : x);
        return a;
    }

    private static double[] darr(JSONArray a) {
        if (a == null) return null;
        double[] d = new double[a.length()];
        for (int i = 0; i < d.length; i++) d[i] = a.optDouble(i, 0);
        return d;
    }

    private static void num(JSONObject o, String key, double v) throws JSONException {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(key, v);
    }
}
