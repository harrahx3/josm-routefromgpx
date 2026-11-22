// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the tags of one relation: the common tags, overridden by the route's own tags, with
 * placeholders such as {@code {file}} replaced. Pure logic, no JOSM dependency.
 */
public final class TagTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    private TagTemplate() {
    }

    /**
     * @param common    tags for every route
     * @param specific  tags of this route; blank values are ignored (the common value applies)
     * @param variables placeholder values; unknown placeholders are left as they are
     * @return the relation tags, {@code type=route} first; tags whose value ends up blank
     *         (e.g. {@code description={desc}} without a description) are left out
     */
    public static Map<String, String> merge(Map<String, String> common, Map<String, String> specific,
            Map<String, String> variables) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("type", "route");
        common.forEach((k, v) -> put(raw, k, v));
        specific.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                put(raw, k, v);
            }
        });
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            String value = substitute(v, variables).trim();
            if (!value.isEmpty()) {
                out.put(k, value);
            }
        });
        return out;
    }

    private static void put(Map<String, String> tags, String key, String value) {
        if (key != null && !key.isBlank() && value != null) {
            tags.put(key.trim(), value);
        }
    }

    public static String substitute(String value, Map<String, String> variables) {
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = variables.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Problems that prevent creating the relation (empty list if none). */
    public static List<String> problems(Map<String, String> tags) {
        List<String> out = new ArrayList<>();
        if (!tags.containsKey("route")) {
            out.add("route=* is missing");
        }
        if (!"route".equals(tags.get("type"))) {
            out.add("type must be route");
        }
        tags.forEach((k, v) -> {
            if (v.length() > 255) {
                out.add(k + ": value longer than 255 characters");
            }
            if (PLACEHOLDER.matcher(v).find()) {
                out.add(k + ": unknown placeholder in \"" + v + "\"");
            }
        });
        return out;
    }

    /** "k=v|k=v" (as stored in the preferences) to a map; the reverse of {@link #format}. */
    public static Map<String, String> parse(List<String> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : entries) {
            int i = kv.indexOf('=');
            if (i > 0) {
                out.put(kv.substring(0, i).trim(), kv.substring(i + 1));
            }
        }
        return out;
    }

    public static List<String> format(Map<String, String> tags) {
        List<String> out = new ArrayList<>();
        tags.forEach((k, v) -> out.add(k + "=" + v));
        return out;
    }
}
