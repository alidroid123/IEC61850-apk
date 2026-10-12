package com.alidev.dfrtools.dfr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CSV import/export for the Line Database. Columns are recognised by their header text, not
 * position, so the utility's own spreadsheet export ("DATA KONDUKTOR": NO, UPT, ULTG, DATA GI /
 * GIS UIT JBT, ..., IMPEDANSI URUTAN POSITIVE / NOL with a second header row) imports as-is, and
 * so does a re-import of this app's own export or a trimmed-down sheet with only a few columns.
 * Comma, semicolon (Indonesian Excel) or tab separated; quoted fields; multi-row headers.
 * Pure Java so it can be verified off-device.
 */
public class LineDataCsv {

    public static class ParseResult {
        public final List<LineData> rows = new ArrayList<>();
        public int skipped;
        /** Header columns that were recognised, for the "format not recognised" message. */
        public int recognisedColumns;
    }

    private enum Col {
        NO, UPT, ULTG, GI, GI_UIP2B, BAY, GI_LAWAN, KV, GRADE, MERK, TIPE, KONDUKTOR, STANDAR, CCC,
        LENGTH, X1, R1, Z1, X0, R0, Z0, ANGLE, B1
    }

    /** Ordered: the first rule that matches a column wins, so the specific ones come first. */
    private static final Object[][] RULES = {
            {Col.GI_UIP2B, "UIP2B|UP2B"},
            {Col.GI_LAWAN, "LAWAN|REMOTE|REF GI"},
            {Col.ANGLE, "SUDUT|ANGLE|DEG"},
            {Col.STANDAR, "STANDAR"},
            {Col.KONDUKTOR, "KONDUKTOR|CONDUCTOR"},
            {Col.MERK, "MERK|BRAND|VENDOR"},
            {Col.TIPE, "TIPE|TYPE"},
            {Col.GRADE, "GRADE"},
            {Col.ULTG, "ULTG"},
            {Col.UPT, "UPT"},
            {Col.BAY, "BAY"},
            {Col.CCC, "CCC"},
            {Col.LENGTH, "PANJANG|LENGTH"},
            {Col.X1, "X1"},
            {Col.R1, "R1"},
            {Col.Z1, "Z1"},
            {Col.X0, "X0"},
            {Col.R0, "R0"},
            {Col.Z0, "Z0"},
            {Col.B1, "B1"},
            {Col.KV, "TEG|KV|VOLT"},
            {Col.GI, "GI|GIS|GARDU|SUBSTATION"},
            {Col.NO, "NO"},
    };

    public static ParseResult parse(Reader in) throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader br = new BufferedReader(in);
        String line;
        while ((line = br.readLine()) != null) lines.add(line);
        if (!lines.isEmpty() && lines.get(0).startsWith("﻿")) lines.set(0, lines.get(0).substring(1));

        ParseResult res = new ParseResult();
        char sep = detectSeparator(lines);
        List<String[]> table = new ArrayList<>();
        for (String l : lines) table.add(split(l, sep));

        // Header = every row before the first one that looks like data; a two-row Excel header is
        // common. Row 0 is always header (the sheet's group row carries "+"/"0" placeholders).
        int firstData = -1;
        for (int i = 1; i < table.size(); i++) {
            if (looksLikeData(table.get(i))) { firstData = i; break; }
        }
        if (firstData <= 0) return res;
        int width = 0;
        for (String[] r : table) width = Math.max(width, r.length);
        String[] names = new String[width];
        for (int c = 0; c < width; c++) {
            StringBuilder sb = new StringBuilder();
            for (int h = 0; h < firstData; h++) {
                String[] r = table.get(h);
                if (c < r.length && !r[c].trim().isEmpty()) sb.append(' ').append(r[c].trim());
            }
            names[c] = sb.toString().trim().toUpperCase(Locale.ROOT);
        }

        Col[] map = new Col[width];
        boolean[] used = new boolean[Col.values().length];
        for (int c = 0; c < width; c++) {
            for (Object[] rule : RULES) {
                Col col = (Col) rule[0];
                if (col != Col.ANGLE && used[col.ordinal()]) continue;
                if (matches(names[c], (String) rule[1])) {
                    map[c] = col;
                    used[col.ordinal()] = true;
                    res.recognisedColumns++;
                    break;
                }
            }
        }
        // Two "SUDUT LINE (DEG)" columns: the one before the zero-sequence block is Z1's angle.
        int firstZero = Integer.MAX_VALUE;
        for (int c = 0; c < width; c++) {
            if (map[c] == Col.X0 || map[c] == Col.R0 || map[c] == Col.Z0) { firstZero = c; break; }
        }

        for (int i = firstData; i < table.size(); i++) {
            String[] r = table.get(i);
            LineData d = new LineData();
            boolean any = false;
            for (int c = 0; c < width && c < r.length; c++) {
                if (map[c] == null) continue;
                String v = r[c].trim();
                if (v.isEmpty()) continue;
                any = true;
                switch (map[c]) {
                    case NO: d.no = v; break;
                    case UPT: d.upt = v; break;
                    case ULTG: d.ultg = v; break;
                    case GI: d.gi = v; break;
                    case GI_UIP2B: d.giUip2b = v; break;
                    case BAY: d.bay = v; break;
                    case GI_LAWAN: d.giLawan = v; break;
                    case GRADE: d.grade = v; break;
                    case MERK: d.merk = v; break;
                    case TIPE: d.tipe = v; break;
                    case KONDUKTOR: d.konduktor = v; break;
                    case STANDAR: d.standarKonduktor = v; break;
                    case KV: d.kv = num(v, sep); break;
                    case CCC: d.ccc = num(v, sep); break;
                    case LENGTH: d.lengthKm = num(v, sep); break;
                    case X1: d.x1 = num(v, sep); break;
                    case R1: d.r1 = num(v, sep); break;
                    case Z1: d.z1 = num(v, sep); break;
                    case X0: d.x0 = num(v, sep); break;
                    case R0: d.r0 = num(v, sep); break;
                    case Z0: d.z0 = num(v, sep); break;
                    case B1: d.b1 = num(v, sep); break;
                    case ANGLE:
                        if (c < firstZero) d.ang1 = num(v, sep); else d.ang0 = num(v, sep);
                        break;
                }
            }
            if (!any) continue;
            d.completeDerived();
            if (d.gi.isEmpty() || !d.hasZ1()) { res.skipped++; continue; }
            res.rows.add(d);
        }
        return res;
    }

    public static final String EXPORT_HEADER = "NO,UPT,ULTG,DATA GI,DATA GI UIP2B,BAY,REF GI LAWAN,TEG (KV),GRADE,"
            + "MERK RELAY,TIPE RELAY,KONDUKTOR,STANDAR KONDUKTOR,CCC (A),PANJANG (KM),X1 (OHM/KM),R1 (OHM/KM),"
            + "Z1 (OHM),SUDUT Z1 (DEG),X0 (OHM/KM),R0 (OHM/KM),Z0 (OHM),SUDUT Z0 (DEG),B1 (US/KM)";

    /** One CSV line in EXPORT_HEADER order (re-importable by parse()). */
    public static String toCsvLine(LineData d, int index) {
        String[] f = {
                d.no.isEmpty() ? String.valueOf(index) : d.no, d.upt, d.ultg, d.gi, d.giUip2b, d.bay, d.giLawan,
                n(d.kv), d.grade, d.merk, d.tipe, d.konduktor, d.standarKonduktor, n(d.ccc), n(d.lengthKm),
                n(d.x1), n(d.r1), n(d.z1), n(d.ang1), n(d.x0), n(d.r0), n(d.z0), n(d.ang0), n(d.b1)
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(',');
            String v = f[i] == null ? "" : f[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
            sb.append(v);
        }
        return sb.toString();
    }

    private static String n(double v) {
        if (Double.isNaN(v)) return "";
        String s = String.format(Locale.US, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean matches(String name, String alternatives) {
        if (name.isEmpty()) return false;
        for (String alt : alternatives.split("\\|")) {
            if (Pattern.compile("(^|[^A-Z0-9])" + Pattern.quote(alt) + "([^A-Z0-9]|$)").matcher(name).find()) return true;
        }
        return false;
    }

    /** A row number in the first cell, or a row where numbers make up a good part of the cells. */
    private static boolean looksLikeData(String[] r) {
        int numeric = 0, nonEmpty = 0;
        for (String c : r) {
            String t = c.trim();
            if (t.isEmpty()) continue;
            nonEmpty++;
            if (t.matches("-?[0-9]+([.,][0-9]+)?")) numeric++;
        }
        if (r.length > 0 && r[0].trim().matches("\\d+") && nonEmpty >= 4) return true;
        return numeric >= 3 && numeric * 4 >= nonEmpty;
    }

    private static char detectSeparator(List<String> lines) {
        int comma = 0, semi = 0, tab = 0;
        for (int i = 0; i < Math.min(lines.size(), 10); i++) {
            String l = lines.get(i);
            for (int k = 0; k < l.length(); k++) {
                char ch = l.charAt(k);
                if (ch == ',') comma++;
                else if (ch == ';') semi++;
                else if (ch == '\t') tab++;
            }
        }
        if (tab > comma && tab > semi) return '\t';
        return semi > comma ? ';' : ',';
    }

    /** RFC-4180-ish split: quoted fields, doubled quotes inside quotes. */
    static String[] split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** Accepts 0.229, 0,229 (semicolon files) and thousands separators like 3,240.00 / 3.240,00. */
    static double num(String v, char sep) {
        String s = v.trim().replace(" ", "");
        if (s.isEmpty()) return Double.NaN;
        int lastDot = s.lastIndexOf('.'), lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastComma > lastDot) s = s.replace(".", "").replace(',', '.');
            else s = s.replace(",", "");
        } else if (lastComma >= 0) {
            s = s.replace(',', '.');
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
