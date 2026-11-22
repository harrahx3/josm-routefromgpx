// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-route tags in a CSV file: a {@code file} column (GPX file name, with or without
 * {@code .gpx}), an optional {@code track} column (track number or name, for files with several
 * tracks), and one column per tag key. The separator ({@code ,}, {@code ;} or tab) is detected
 * from the header line; the file is UTF-8 (a BOM is skipped). Pure logic, no JOSM dependency.
 */
public final class CsvTags {

    public static final String FILE = "file";
    public static final String TRACK = "track";

    private CsvTags() {
    }

    /** Header and rows, each row as a map from header to value. */
    public record Table(List<String> header, List<Map<String, String>> rows) {
        /** The tag keys: every column except {@code file} and {@code track}. */
        public List<String> keys() {
            return header.stream().filter(h -> !h.equals(FILE) && !h.equals(TRACK)).toList();
        }
    }

    public static Table read(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        StringBuilder all = new StringBuilder();
        char[] buf = new char[8192];
        for (int n; (n = r.read(buf)) > 0; ) {
            all.append(buf, 0, n);
        }
        String text = all.toString();
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char sep = detectSeparator(text);
        List<List<String>> lines = parse(text, sep);
        if (lines.isEmpty()) {
            throw new IOException("empty CSV file");
        }
        List<String> header = new ArrayList<>();
        for (String h : lines.get(0)) {
            header.add(h.trim());
        }
        int fileCol = indexOfIgnoreCase(header, FILE);
        if (fileCol < 0) {
            throw new IOException("the CSV file has no \"" + FILE + "\" column");
        }
        header.set(fileCol, FILE);
        int trackCol = indexOfIgnoreCase(header, TRACK);
        if (trackCol >= 0) {
            header.set(trackCol, TRACK);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> line : lines.subList(1, lines.size())) {
            if (line.stream().allMatch(String::isBlank)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                if (!header.get(i).isEmpty()) {
                    row.put(header.get(i), i < line.size() ? line.get(i).trim() : "");
                }
            }
            rows.add(row);
        }
        return new Table(header.stream().filter(h -> !h.isEmpty()).toList(), rows);
    }

    private static int indexOfIgnoreCase(List<String> list, String s) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(s)) {
                return i;
            }
        }
        return -1;
    }

    static char detectSeparator(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        char best = ',';
        long bestCount = 0;
        for (char c : new char[] {',', ';', '\t'}) {
            long count = first.chars().filter(ch -> ch == c).count();
            if (count > bestCount) {
                best = c;
                bestCount = count;
            }
        }
        return best;
    }

    /** RFC 4180 parsing: quoted fields may contain the separator, quotes ("") and line breaks. */
    static List<List<String>> parse(String text, char sep) {
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                line.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                line.add(field.toString());
                field.setLength(0);
                lines.add(line);
                line = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !line.isEmpty()) {
            line.add(field.toString());
            lines.add(line);
        }
        return lines;
    }

    public static void write(Writer out, List<String> header, List<List<String>> rows) throws IOException {
        writeLine(out, header);
        for (List<String> row : rows) {
            writeLine(out, row);
        }
        out.flush();
    }

    private static void writeLine(Writer out, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            String f = fields.get(i) == null ? "" : fields.get(i);
            if (f.contains(",") || f.contains("\"") || f.contains("\n") || f.contains("\r")) {
                f = "\"" + f.replace("\"", "\"\"") + "\"";
            }
            out.write(i == 0 ? f : "," + f);
        }
        out.write("\r\n");
    }

    /** File name for matching: without folder and without .gpx, lower case. */
    static String fileKey(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".gpx")) {
            n = n.substring(0, n.length() - 4);
        }
        return n.toLowerCase(Locale.ROOT);
    }

    /** True if the CSV row is about this track. */
    public static boolean matches(Map<String, String> row, TrackSource t) {
        if (!fileKey(row.getOrDefault(FILE, "")).equals(fileKey(t.file))) {
            return false;
        }
        String track = row.getOrDefault(TRACK, "").trim();
        return track.isEmpty() || track.equals(String.valueOf(t.number)) || track.equalsIgnoreCase(t.trackName);
    }

    /**
     * Copies the CSV values into the matching tracks' own tags (blank cells are skipped).
     * @return the CSV rows that matched no track
     */
    public static List<Map<String, String>> apply(Table table, List<TrackSource> tracks) {
        List<Map<String, String>> unmatched = new ArrayList<>();
        for (Map<String, String> row : table.rows()) {
            boolean found = false;
            for (TrackSource t : tracks) {
                if (matches(row, t)) {
                    found = true;
                    for (String key : table.keys()) {
                        String v = row.getOrDefault(key, "");
                        if (!v.isBlank()) {
                            t.tags.put(key, v);
                        }
                    }
                }
            }
            if (!found) {
                unmatched.add(row);
            }
        }
        return unmatched;
    }
}
