package com.alidev.dfrtools.dfr;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Device list CSV: UPT,ULTG,NAMA GI,BAY,DEVICE,IP,MERK,TYPE. Reads what this app exports, its
 * template, Excel saves (';'-separated in the Indonesian locale, quoted fields) and the older
 * 6-column layout without UPT/ULTG - by header names when there is a header row, otherwise by
 * where the IP column sits. Pure Java so it can be checked off-device.
 */
final class DeviceCsv {

    static final String[] HEADER = {"UPT", "ULTG", "NAMA GI", "BAY", "DEVICE", "IP", "MERK", "TYPE"};

    static final int UPT = 0, ULTG = 1, GI = 2, BAY = 3, DEVICE = 4, IP = 5, MERK = 6, TYPE = 7;

    /** One device line; values in HEADER order. */
    static class Row {
        final String[] v = {"", "", "", "", "", "", "", ""};

        String get(int field) { return v[field]; }
    }

    private DeviceCsv() { }

    /** Every row with an IP, in file order. */
    static List<Row> parse(BufferedReader reader) throws IOException {
        List<Row> rows = new ArrayList<>();
        int[] columns = null; // CSV column of each field, -1 = absent
        char sep = ',';
        boolean first = true;
        String line;
        while ((line = reader.readLine()) != null) {
            if (first) {
                if (line.startsWith("﻿")) line = line.substring(1);
                if (line.trim().isEmpty()) continue;
                sep = detectSeparator(line);
            }
            if (line.trim().isEmpty()) continue;
            List<String> cells = split(line, sep);
            if (first) {
                first = false;
                columns = headerColumns(cells);
                if (columns != null) continue; // header row
            }
            int[] map = columns != null ? columns : positionalColumns(cells);
            Row row = new Row();
            for (int f = 0; f < HEADER.length; f++) {
                int c = map[f];
                if (c >= 0 && c < cells.size()) row.v[f] = clean(cells.get(c));
            }
            if (!row.v[IP].isEmpty()) rows.add(row);
        }
        return rows;
    }

    /** One CSV line, every value quoted (names may contain commas). */
    static String line(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String s = values[i] == null ? "" : values[i];
            sb.append('"').append(s.replace("\"", "\"\"")).append('"');
        }
        return sb.append('\n').toString();
    }

    static String headerLine() {
        StringBuilder sb = new StringBuilder();
        for (String h : HEADER) sb.append(sb.length() > 0 ? "," : "").append(h);
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------

    private static char detectSeparator(String line) {
        int commas = 0, semis = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (!quoted && ch == ',') commas++;
            else if (!quoted && ch == ';') semis++;
        }
        return semis > commas ? ';' : ',';
    }

    /** Splits one line, honouring "quoted, values" and "" escapes. */
    static List<String> split(String line, char sep) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    private static String clean(String s) {
        return s.replace("\"", "").trim();
    }

    /** Field -> column from a header row, or null when `cells` isn't one (no IP column name). */
    private static int[] headerColumns(List<String> cells) {
        int[] map = {-1, -1, -1, -1, -1, -1, -1, -1};
        for (int c = 0; c < cells.size(); c++) {
            int f = headerField(cells.get(c));
            if (f >= 0 && map[f] < 0) map[f] = c;
        }
        return map[IP] >= 0 ? map : null;
    }

    private static int headerField(String cell) {
        String h = cell.replace("\"", "").trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        switch (h) {
            case "UPT": return UPT;
            case "ULTG": return ULTG;
            case "GI": case "NAMA GI": case "GARDU INDUK": case "NAMA GARDU INDUK": case "SUBSTATION": case "SUBSTATION NAME": return GI;
            case "BAY": case "NAMA BAY": case "BAY NAME": return BAY;
            case "DEVICE": case "NAMA DEVICE": case "DEVICE NAME": case "RELAY": return DEVICE;
            case "IP": case "ALAMAT IP": case "IP ADDRESS": return IP;
            case "MERK": case "MERK RELAY": case "BRAND": case "RELAY BRAND": return MERK;
            case "TYPE": case "TIPE": case "TIPE RELAY": case "RELAY TYPE": return TYPE;
            default: return -1;
        }
    }

    /** No header: the 8-column layout when column 6 holds the IP, else the old 6-column one. */
    private static int[] positionalColumns(List<String> cells) {
        boolean wide = cells.size() > 5 && looksLikeIp(cells.get(5)) && !(cells.size() > 3 && looksLikeIp(cells.get(3)));
        return wide ? new int[]{0, 1, 2, 3, 4, 5, 6, 7} : new int[]{-1, -1, 0, 1, 2, 3, 4, 5};
    }

    static boolean looksLikeIp(String s) {
        return clean(s).matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
