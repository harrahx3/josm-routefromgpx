// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.plugins.routefromgpx.core.RouteProfile;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Checks {@link RouteProfile} (which ways each kind of route may use, related route types) and
 * {@link Areas#downloadAreas} (long tracks downloaded in boxes). Run by {@code ./build.sh test}.
 */
public final class RouteProfileCheck {
    private static int failures;

    static Way way(String... kv) {
        Way w = new Way();
        w.setNodes(List.of(new Node(new LatLon(45, 1)), new Node(new LatLon(45, 1.001))));
        for (int i = 0; i < kv.length; i += 2) {
            w.put(kv[i], kv[i + 1]);
        }
        return w;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        Config.setPreferencesInstance(new Preferences());
        RouteProfile hiking = RouteProfile.of("hiking");
        RouteProfile bike = RouteProfile.of("bicycle");
        RouteProfile horse = RouteProfile.of("horse");

        check("hiking: footway", hiking.usable(way("highway", "footway")));
        check("hiking: steps", hiking.usable(way("highway", "steps")));
        check("hiking: not foot=no", !hiking.usable(way("highway", "cycleway", "foot", "no")));
        check("hiking: bicycle=no is fine", hiking.usable(way("highway", "path", "bicycle", "no")));
        check("hiking: not motorway", !hiking.usable(way("highway", "motorway")));
        check("bicycle: track", bike.usable(way("highway", "track")));
        check("bicycle: not bicycle=no", !bike.usable(way("highway", "path", "bicycle", "no")));
        check("bicycle: foot=no is fine", bike.usable(way("highway", "cycleway", "foot", "no")));
        check("bicycle: not vehicle=no", !bike.usable(way("highway", "track", "vehicle", "no")));
        check("bicycle: bicycle=yes beats vehicle=no", bike.usable(way("highway", "track", "vehicle", "no", "bicycle", "yes")));
        check("bicycle: not steps", !bike.usable(way("highway", "steps")));
        check("bicycle: steps with bicycle=yes", bike.usable(way("highway", "steps", "bicycle", "yes")));
        check("bicycle: not access=private", !bike.usable(way("highway", "service", "access", "private")));
        check("bicycle: access=no with bicycle=designated", bike.usable(way("highway", "track", "access", "no", "bicycle", "designated")));
        check("bicycle: not motorroad", !bike.usable(way("highway", "trunk", "motorroad", "yes")));
        check("horse: not horse=no", !horse.usable(way("highway", "bridleway", "horse", "no")));
        check("related: mtb is a bicycle route", "mtb".matches(bike.related()) && "mtb".matches(RouteProfile.of("mtb").related()));
        check("related: walking is a hiking route", "walking".matches(hiking.related()) && !"bicycle".matches(hiking.related()));
        check("unknown type: related to itself only", "canoe".matches(RouteProfile.of("canoe").related())
                && !"hiking".matches(RouteProfile.of("canoe").related()));

        // a hike: one box; a 1 degree long route (~110 km): boxes along it, overlapping, each small
        List<Pt> hike = List.of(new Pt(45, 1), new Pt(45.02, 1.03));
        check("short track: one download", Areas.downloadAreas(hike, 0.25, 0.01).size() == 1);
        List<Pt> ride = new ArrayList<>();
        for (int i = 0; i <= 1000; i++) {
            ride.add(new Pt(45 + i * 0.001, 1 + i * 0.0008));
        }
        List<Bounds> boxes = Areas.downloadAreas(ride, 0.25, 0.01);
        boolean small = boxes.stream().allMatch(b -> Areas.area(b) <= 0.01);
        boolean allCovered = ride.stream().allMatch(p -> boxes.stream().anyMatch(b -> b.contains(new LatLon(p.lat(), p.lon()))));
        check("long track: " + boxes.size() + " boxes, each <= 0.01 deg2, covering every point", boxes.size() > 1 && small && allCovered);

        System.out.println(failures == 0 ? "RouteProfileCheck: all checks passed" : failures + " check(s) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
