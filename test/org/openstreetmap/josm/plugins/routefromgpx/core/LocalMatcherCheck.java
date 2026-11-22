// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Edge;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.GapRun;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.MatchData;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Segment;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.WayGeom;

/**
 * Checks of LocalMatcher + RoutePlanner on synthetic ways (no JOSM data set needed):
 *   java -cp build/classes:build/test org.openstreetmap.josm.plugins.routefromgpx.core.LocalMatcherCheck
 * Exits 1 on the first failure.
 */
public final class LocalMatcherCheck {
    static final double LAT0 = 45.1, LON0 = 1.2;
    static final double M_LAT = 1 / 111195.0, M_LON = 1 / (111195.0 * Math.cos(Math.toRadians(LAT0)));
    static final Random RND = new Random(42);

    static Pt xy(double x, double y) {
        return new Pt(LAT0 + y * M_LAT, LON0 + x * M_LON);
    }

    /** Way through the given node ids, positions as {x, y} metres. */
    static WayGeom way(long id, long[] nodes, double[][] pos) {
        List<Pt> g = new ArrayList<>();
        for (double[] p : pos) {
            g.add(xy(p[0], p[1]));
        }
        return new WayGeom(id, nodes, g);
    }

    /** Points every 10 m along the polyline, with GPS noise. */
    static List<Pt> trace(double noise, double[]... pts) {
        List<Pt> out = new ArrayList<>();
        for (int i = 0; i + 1 < pts.length; i++) {
            double len = Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]);
            for (double d = 0; d < len; d += 10) {
                double t = d / len;
                out.add(xy(pts[i][0] + t * (pts[i + 1][0] - pts[i][0]) + RND.nextGaussian() * noise,
                        pts[i][1] + t * (pts[i + 1][1] - pts[i][1]) + RND.nextGaussian() * noise));
            }
        }
        double[] last = pts[pts.length - 1];
        out.add(xy(last[0], last[1]));
        return out;
    }

    static List<Segment> segments(List<WayGeom> ways, List<Pt> tr, MatchData r, List<GapRun> runsOut) {
        java.util.Map<Long, WayGeom> byId = new java.util.HashMap<>();
        ways.forEach(w -> byId.put(w.id(), w));
        List<GapRun> runs = RoutePlanner.addBreaks(RoutePlanner.findGaps(tr, r.matched()), r.breaks());
        runsOut.addAll(runs);
        List<Edge> edges = runs.isEmpty() ? r.edges() : RoutePlanner.cutOutGaps(r.edges(), r.shape(), r.matched(), runs);
        return RoutePlanner.buildSegments(edges, r.shape(), byId, new ArrayList<>());
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        // 1. one straight way, noisy trace along it
        List<WayGeom> ways = List.of(way(1, new long[] {1, 2, 3, 4}, new double[][] {{0, 0}, {100, 0}, {200, 0}, {300, 0}}));
        List<Pt> tr = trace(5, new double[] {0, 0}, new double[] {300, 0});
        MatchData r = new LocalMatcher(ways, tr).match(tr);
        List<GapRun> runs = new ArrayList<>();
        List<Segment> segs = segments(ways, tr, r, runs);
        check(segs.size() == 1 && segs.get(0).start == 0 && segs.get(0).end == 3 && runs.isEmpty(), "straight way: " + segs);

        // 2. two ways that don't share a node (3 m apart): a break, reported as a gap
        ways = List.of(way(1, new long[] {1, 2}, new double[][] {{0, 0}, {150, 0}}),
                way(2, new long[] {3, 4}, new double[][] {{153, 0}, {300, 0}}));
        tr = trace(1, new double[] {0, 0}, new double[] {300, 0});
        r = new LocalMatcher(ways, tr).match(tr);
        runs = new ArrayList<>();
        segs = segments(ways, tr, r, runs);
        check(r.breaks().size() == 1 && runs.size() == 1 && segs.size() == 2, "unconnected ways: breaks "
                + r.breaks().size() + ", gaps " + runs.size() + ", " + segs);

        // 3. junction with a side branch; the trace wanders 8 m onto it: no extra member
        ways = List.of(way(1, new long[] {1, 2, 3}, new double[][] {{0, 0}, {150, 0}, {300, 0}}),
                way(2, new long[] {2, 5, 6}, new double[][] {{150, 0}, {150, 6}, {150, 200}}));
        tr = trace(0, new double[] {0, 0}, new double[] {140, 0}, new double[] {150, 8}, new double[] {160, 0},
                new double[] {300, 0});
        r = new LocalMatcher(ways, tr).match(tr);
        segs = segments(ways, tr, r, new ArrayList<>());
        check(segs.size() == 1 && segs.get(0).wayId == 1, "jitter at a junction: " + segs);

        // 4. 60 m out-and-back where the side way goes on: a turnaround, dropped
        tr = trace(0, new double[] {0, 0}, new double[] {150, 0}, new double[] {150, 60}, new double[] {150, 0},
                new double[] {300, 0});
        ways = List.of(way(1, new long[] {1, 2, 3}, new double[][] {{0, 0}, {150, 0}, {300, 0}}),
                way(2, new long[] {2, 5, 6}, new double[][] {{150, 0}, {150, 60}, {150, 200}}));
        r = new LocalMatcher(ways, tr).match(tr);
        segs = segments(ways, tr, r, new ArrayList<>());
        check(segs.size() == 1 && segs.get(0).wayId == 1, "turnaround mid-way: " + segs);

        // 5. same 60 m out-and-back to a dead end: a real spur, kept as two members
        ways = List.of(way(1, new long[] {1, 2, 3}, new double[][] {{0, 0}, {150, 0}, {300, 0}}),
                way(2, new long[] {2, 5}, new double[][] {{150, 0}, {150, 60}}));
        r = new LocalMatcher(ways, tr).match(tr);
        segs = segments(ways, tr, r, new ArrayList<>());
        check(segs.size() == 4 && segs.get(1).wayId == 2 && segs.get(2).wayId == 2, "spur to a dead end: " + segs);

        // 6. track leaves the ways for 400 m (unmapped path): one gap, ways on both sides kept
        ways = List.of(way(1, new long[] {1, 2}, new double[][] {{0, 0}, {200, 0}}),
                way(3, new long[] {2, 7, 8}, new double[][] {{200, 0}, {200, -500}, {600, -500}}),
                way(2, new long[] {3, 4}, new double[][] {{600, 0}, {800, 0}}),
                way(4, new long[] {8, 3}, new double[][] {{600, -500}, {600, 0}}));
        tr = trace(2, new double[] {0, 0}, new double[] {800, 0});
        r = new LocalMatcher(ways, tr).match(tr);
        runs = new ArrayList<>();
        segs = segments(ways, tr, r, runs);
        check(runs.size() == 1 && segs.size() == 2 && segs.get(0).wayId == 1 && segs.get(1).wayId == 2,
                "unmapped stretch: gaps " + runs.size() + ", " + segs);
        System.out.println("all checks passed");
    }
}
