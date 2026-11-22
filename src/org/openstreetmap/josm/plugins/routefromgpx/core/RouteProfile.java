// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx.core;

import java.util.Map;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.osm.Way;

/**
 * Per route type ({@code route=*}) settings: which existing routes count as the same kind
 * (duplicate check) and which ways the matcher may use.
 *
 * @param related         regex of the {@code route=*} values of the same kind
 * @param accessKeys      access keys, most specific first (e.g. bicycle, vehicle); the first one
 *                        present decides, then {@code access=*}
 * @param defaultExcluded highway values not usable unless an access key above allows them
 */
public record RouteProfile(String related, String[] accessKeys, String defaultExcluded) {

    static final RouteProfile FOOT = new RouteProfile("hiking|foot|walking", new String[] {"foot"}, "");
    static final RouteProfile BICYCLE = new RouteProfile("bicycle|mtb", new String[] {"bicycle", "vehicle"}, "steps");

    private static final Map<String, RouteProfile> PROFILES = Map.of(
            "hiking", FOOT,
            "foot", FOOT,
            "walking", FOOT,
            "running", new RouteProfile("running", new String[] {"foot"}, ""),
            "bicycle", BICYCLE,
            "mtb", BICYCLE,
            "horse", new RouteProfile("horse", new String[] {"horse"}, "steps"),
            "inline_skates", new RouteProfile("inline_skates", new String[] {"inline_skates"}, "steps|track|path"));

    /** Route types offered in the user interface (any other value can be typed). */
    public static final String[] KNOWN = {"hiking", "foot", "walking", "running", "bicycle", "mtb", "horse", "inline_skates"};

    /** Settings for {@code route}; an unknown type is only related to itself, matched like a foot route. */
    public static RouteProfile of(String route) {
        RouteProfile p = PROFILES.get(route);
        return p != null ? p : new RouteProfile(Pattern.quote(route), new String[] {"foot"}, "");
    }

    /** Ways this kind of route can use: highway=* except motorways, construction, areas, and access rules. */
    public boolean usable(Way w) {
        String hw = w.get("highway");
        if (hw == null || w.isDeleted() || w.isIncomplete() || w.getNodesCount() < 2 || w.hasTag("area", "yes")
                || hw.matches("motorway|motorway_link|construction|proposed|abandoned|disused|razed|raceway|bus_guideway|escape|rest_area|services|platform|corridor")) {
            return false;
        }
        for (String key : accessKeys) {
            String v = w.get(key);
            if (v != null) {
                return !v.matches("no|private|use_sidepath");
            }
        }
        if ((!defaultExcluded.isEmpty() && hw.matches(defaultExcluded)) || w.hasTag("motorroad", "yes")) {
            return false;
        }
        String access = w.get("access");
        return access == null || !access.matches("no|private");
    }
}
