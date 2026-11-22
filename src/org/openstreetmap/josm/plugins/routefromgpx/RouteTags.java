// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.routefromgpx.core.RouteProfile;

/**
 * The tags commonly found on route relations, with the values usually given to them. Offered as
 * suggestions in the dialog (any other key or value can still be typed). Pure data, no JOSM
 * dependency.
 */
public final class RouteTags {

    private RouteTags() {
    }

    /** Key to suggested values (possibly none), in the order they are offered. */
    public static final Map<String, List<String>> SUGGESTED = build();

    /** Keys whose suggestions are placeholders computed from the GPX track. */
    public static final Set<String> FROM_TRACK = Set.of("distance", "ascent", "descent");

    private static Map<String, List<String>> build() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("route", List.of(RouteProfile.KNOWN));
        m.put("name", List.of("{track}", "{file}"));
        m.put("ref", List.of("{file}", "{n}"));
        m.put("network", List.of("iwn", "nwn", "rwn", "lwn", "icn", "ncn", "rcn", "lcn"));
        m.put("network:type", List.of("node_network"));
        m.put("operator", List.of());
        m.put("distance", List.of("{distance}"));
        m.put("ascent", List.of("{ascent}"));
        m.put("descent", List.of("{descent}"));
        m.put("roundtrip", List.of("yes", "no"));
        m.put("from", List.of());
        m.put("to", List.of());
        m.put("colour", List.of("red", "blue", "green", "yellow", "orange", "purple", "white", "black", "brown"));
        m.put("osmc:symbol", List.of("red:white:red_bar", "blue:white:blue_bar", "yellow:white:yellow_bar",
                "green:white:green_bar", "red:white:red_lower", "white:red:white_bar"));
        m.put("symbol", List.of());
        m.put("description", List.of("{desc}"));
        m.put("website", List.of());
        m.put("source", List.of("survey", "GPS", "gpx", "survey;gpx", "local_knowledge"));
        m.put("source:geometry", List.of("GPS", "gpx", "survey"));
        m.put("state", List.of("proposed", "alternate", "temporary", "connection"));
        m.put("educational", List.of("yes"));
        m.put("tourism", List.of());
        m.put("wikidata", List.of());
        m.put("wikipedia", List.of());
        m.put("note", List.of());
        m.put("fixme", List.of());
        return Collections.unmodifiableMap(m);
    }

    /** Suggested values for {@code key} (empty list if none or unknown key). */
    public static List<String> values(String key) {
        return key == null ? List.of() : SUGGESTED.getOrDefault(key.trim(), List.of());
    }
}
