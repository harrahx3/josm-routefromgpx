// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.util.List;
import java.util.Locale;

import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;

/**
 * Length and elevation gain of a GPX track, for the {@code {distance}}, {@code {ascent}} and
 * {@code {descent}} placeholders. Pure logic, no JOSM dependency.
 *
 * @param km      length of the track in kilometres
 * @param ascent  total climb in metres, NaN if the track has no elevation
 * @param descent total descent in metres, NaN if the track has no elevation
 */
public record TrackStats(double km, double ascent, double descent) {

    /** Elevation changes smaller than this (metres) are taken as GPS noise. */
    static final double NOISE = 5;

    public static final TrackStats NONE = new TrackStats(0, Double.NaN, Double.NaN);

    /**
     * @param points    the track
     * @param elevation elevation of each point (NaN where unknown); may be null
     */
    public static TrackStats of(List<Pt> points, double[] elevation) {
        double m = 0;
        for (int i = 1; i < points.size(); i++) {
            m += meters(points.get(i - 1), points.get(i));
        }
        double up = 0;
        double down = 0;
        double ref = Double.NaN;
        int known = 0;
        if (elevation != null) {
            // hysteresis: count a change only once it exceeds NOISE from the last retained level
            for (double e : elevation) {
                if (Double.isNaN(e)) {
                    continue;
                }
                known++;
                if (Double.isNaN(ref)) {
                    ref = e;
                } else if (e - ref >= NOISE) {
                    up += e - ref;
                    ref = e;
                } else if (ref - e >= NOISE) {
                    down += ref - e;
                    ref = e;
                }
            }
        }
        return known < 2 ? new TrackStats(m / 1000, Double.NaN, Double.NaN) : new TrackStats(m / 1000, up, down);
    }

    static double meters(Pt a, Pt b) {
        double r = 6_371_008.8;
        double la1 = Math.toRadians(a.lat());
        double la2 = Math.toRadians(b.lat());
        double dLa = la2 - la1;
        double dLo = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Length as OSM's distance=* wants it: kilometres, one decimal ("" for an empty track). */
    public String distanceTag() {
        return km > 0 ? String.format(Locale.ROOT, "%.1f", km) : "";
    }

    /** Ascent in whole metres, "" if unknown. */
    public String ascentTag() {
        return Double.isNaN(ascent) ? "" : String.valueOf(Math.round(ascent));
    }

    /** Descent in whole metres, "" if unknown. */
    public String descentTag() {
        return Double.isNaN(descent) ? "" : String.valueOf(Math.round(descent));
    }
}
