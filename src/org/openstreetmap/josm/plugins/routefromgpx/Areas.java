// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;

/** Bounding boxes of a track, and the boxes to download so the data layer covers it. */
public final class Areas {

    /** Margin around the track, in degrees (~200 m), so ways just beside it are downloaded too. */
    static final double MARGIN_DEG = 0.002;

    private Areas() {
    }

    /**
     * The track's bounding box if the server accepts it ({@code maxArea}, square degrees), else
     * overlapping boxes of at most {@code tileArea} along the track (long routes).
     */
    public static List<Bounds> downloadAreas(List<Pt> trace, double maxArea, double tileArea) {
        Bounds all = bounds(trace);
        if (area(all) <= maxArea) {
            return List.of(all);
        }
        List<Bounds> out = new ArrayList<>();
        Pt prev = trace.get(0);
        Bounds box = bounds(List.of(prev));
        for (Pt p : trace.subList(1, trace.size())) {
            Bounds grown = new Bounds(box);
            grown.extend(p.lat() + MARGIN_DEG, p.lon() + MARGIN_DEG);
            grown.extend(p.lat() - MARGIN_DEG, p.lon() - MARGIN_DEG);
            if (area(grown) > tileArea) {
                out.add(box);
                box = bounds(List.of(prev, p));   // starts at the previous point: the boxes overlap
            } else {
                box = grown;
            }
            prev = p;
        }
        out.add(box);
        return out;
    }

    public static double area(Bounds b) {
        return (b.getMaxLat() - b.getMinLat()) * (b.getMaxLon() - b.getMinLon());
    }

    /** Bounding box of the points, plus {@link #MARGIN_DEG}. */
    public static Bounds bounds(List<Pt> pts) {
        double minLat = 90, minLon = 180, maxLat = -90, maxLon = -180;
        for (Pt p : pts) {
            minLat = Math.min(minLat, p.lat());
            maxLat = Math.max(maxLat, p.lat());
            minLon = Math.min(minLon, p.lon());
            maxLon = Math.max(maxLon, p.lon());
        }
        return new Bounds(minLat - MARGIN_DEG, minLon - MARGIN_DEG, maxLat + MARGIN_DEG, maxLon + MARGIN_DEG);
    }

    /** True if one downloaded area of the data set contains the whole box. */
    public static boolean covered(DataSet ds, Bounds b) {
        for (Bounds db : ds.getDataSourceBounds()) {
            if (db.contains(b.getMin()) && db.contains(b.getMax())) {
                return true;
            }
        }
        return false;
    }
}
