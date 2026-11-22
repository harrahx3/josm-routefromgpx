// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure matching logic, no JOSM dependency: turns a map-matching answer (from {@link LocalMatcher}) into an ordered list of way segments, the nodes where ways must be split, and the gaps (stretches of
 * track with no way under them, usually a path missing from OSM).
 */
public final class RoutePlanner {

    public static final double MIN_SPACING_M = 10;
    public static final int MAX_POINTS = 1500;
    public static final double GOOD_POINT_DISTANCE_M = 25;
    public static final double NODE_SNAP_TOLERANCE_M = 5;
    public static final double QUALITY_MIN_MATCH_RATIO = 0.95;
    public static final double QUALITY_MIN_LENGTH_RATIO = 0.85;
    public static final double QUALITY_MAX_LENGTH_RATIO = 1.20;
    public static final double DUPLICATE_MIN_OVERLAP = 0.8;
    public static final double GAP_OFFSET_M = 25;
    public static final double GAP_MIN_OFFSET_M = 50;
    public static final double GAP_MIN_LENGTH_M = 50;

    private RoutePlanner() {
    }

    /** A point, latitude/longitude in degrees. */
    public record Pt(double lat, double lon) {
    }

    /** A way as seen by the planner: node ids and their positions. */
    public record WayGeom(long id, long[] nodes, List<Pt> geometry) {
        public boolean closed() {
            return nodes.length > 2 && nodes[0] == nodes[nodes.length - 1];
        }
    }

    /** A matched edge (wayId 0 if none), or a gap marker (gap &gt;= 0). */
    public static final class Edge {
        public final long wayId;
        public int begin;
        public int end;
        public final int gap;
        public int afterSegment = -1;

        public Edge(long wayId, int begin, int end) {
            this(wayId, begin, end, -1);
        }

        private Edge(long wayId, int begin, int end, int gap) {
            this.wayId = wayId;
            this.begin = begin;
            this.end = end;
            this.gap = gap;
        }

        static Edge gapMarker(int k) {
            return new Edge(0, -1, -1, k);
        }

        public boolean isGap() {
            return gap >= 0;
        }
    }

    /** A matched point. type is matched / interpolated / unmatched. */
    public record MatchedPoint(String type, double distance, int edgeIndex, Double lat, Double lon) {
        boolean unmatched() {
            return "unmatched".equals(type);
        }
    }

    /** Part of a way, from node index start to end (indices into the way's node list). */
    public static final class Segment {
        public final long wayId;
        public int start;
        public int end;

        public Segment(long wayId, int start, int end) {
            this.wayId = wayId;
            this.start = start;
            this.end = end;
        }

        public boolean forward() {
            return end >= start;
        }

        @Override
        public String toString() {
            return "Segment(" + wayId + ", " + start + ", " + end + ")";
        }
    }

    /**
     * A map-matching answer: the matched path (shape), the way edges along it (indices into
     * shape), one matched point per trace point, and breaks: pairs of trace point indices
     * {i, j} between which the matched path is discontinuous (no route found).
     */
    public record MatchData(List<Pt> shape, List<Edge> edges, List<MatchedPoint> matched, List<int[]> breaks) {
    }

    /** A run of trace points [start, end] with no way under them; before/after = -1 at the ends. */
    public record GapRun(int start, int end, int before, int after) {
    }

    /** A gap as reported to the user. */
    public record Gap(int afterSegment, List<Pt> track, double lengthM) {
    }

    // ------------------------------------------------------------------ geometry

    public static double haversineM(Pt a, Pt b) {
        double lat1 = Math.toRadians(a.lat), lat2 = Math.toRadians(b.lat);
        double dLat = lat2 - lat1, dLon = Math.toRadians(b.lon - a.lon);
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.sqrt(h));
    }

    public static double lengthM(List<Pt> pts) {
        double sum = 0;
        for (int i = 1; i < pts.size(); i++) {
            sum += haversineM(pts.get(i - 1), pts.get(i));
        }
        return sum;
    }

    public static List<Pt> thin(List<Pt> points) {
        if (points.size() < 3) {
            return new ArrayList<>(points);
        }
        double spacing = MIN_SPACING_M;
        while (true) {
            List<Pt> kept = new ArrayList<>();
            kept.add(points.get(0));
            for (Pt p : points.subList(1, points.size() - 1)) {
                if (haversineM(kept.get(kept.size() - 1), p) >= spacing) {
                    kept.add(p);
                }
            }
            kept.add(points.get(points.size() - 1));
            if (kept.size() <= MAX_POINTS) {
                return kept;
            }
            spacing *= 1.5;
        }
    }

    static int nearestIndex(List<Pt> geometry, Pt p) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < geometry.size(); i++) {
            double d = haversineM(geometry.get(i), p);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ gaps

    private static boolean off(MatchedPoint m) {
        return m.unmatched() || m.distance() > GAP_OFFSET_M;
    }

    public static List<GapRun> findGaps(List<Pt> points, List<MatchedPoint> matched) {
        List<GapRun> gaps = new ArrayList<>();
        int n = Math.min(points.size(), matched.size());
        int i = 0;
        while (i < n) {
            if (!off(matched.get(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n && off(matched.get(j + 1))) {
                j++;
            }
            int before = i > 0 ? i - 1 : -1;
            int after = j + 1 < n ? j + 1 : -1;
            boolean far = false;
            for (MatchedPoint m : matched.subList(i, j + 1)) {
                far |= m.unmatched() || m.distance() >= GAP_MIN_OFFSET_M;
            }
            List<Pt> span = points.subList(before < 0 ? i : before, (after < 0 ? j : after) + 1);
            if (far && lengthM(span) >= GAP_MIN_LENGTH_M) {
                gaps.add(new GapRun(i, j, before, after));
            }
            i = j + 1;
        }
        return gaps;
    }

    /**
     * Add the breaks of the matched path to the gaps (a break is a gap even when both points are
     * near a way, e.g. two ways that don't share a node). Overlapping runs are merged.
     */
    public static List<GapRun> addBreaks(List<GapRun> runs, List<int[]> breaks) {
        if (breaks.isEmpty()) {
            return runs;
        }
        List<GapRun> all = new ArrayList<>(runs);
        for (int[] b : breaks) {
            all.add(new GapRun(b[0] + 1, b[1] - 1, b[0], b[1]));
        }
        all.sort((a, b) -> Integer.compare(lo(a), lo(b)));
        List<GapRun> out = new ArrayList<>();
        for (GapRun g : all) {
            GapRun cur = out.isEmpty() ? null : out.get(out.size() - 1);
            if (cur != null && (lo(g) < hi(cur) || cur.after() < 0)) {
                out.set(out.size() - 1, new GapRun(Math.min(cur.start(), g.start()), Math.max(cur.end(), g.end()),
                        cur.before() < 0 || g.before() < 0 ? -1 : Math.min(cur.before(), g.before()),
                        cur.after() < 0 || g.after() < 0 ? -1 : Math.max(cur.after(), g.after())));
            } else {
                out.add(g);
            }
        }
        return out;
    }

    private static int lo(GapRun g) {
        return g.before() < 0 ? g.start() : g.before();
    }

    private static int hi(GapRun g) {
        return g.after() < 0 ? g.end() : g.after();
    }

    /** Remove the matcher's detour between the two good points of each gap; put a marker instead. */
    public static List<Edge> cutOutGaps(List<Edge> edges, List<Pt> shape, List<MatchedPoint> matched, List<GapRun> gaps) {
        int last = shape.size() - 1;
        List<int[]> kept = new ArrayList<>();   // null = empty interval
        int start = 0;
        for (GapRun g : gaps) {
            Integer sa = g.before() >= 0 ? snappedVertex(edges, shape, matched.get(g.before())) : null;
            Integer sb = g.after() >= 0 ? snappedVertex(edges, shape, matched.get(g.after())) : null;
            kept.add(sa == null ? null : new int[] {start, sa});
            start = sb == null ? last + 1 : Math.max(sb, sa == null ? 0 : sa);
        }
        kept.add(start <= last ? new int[] {start, last} : null);

        List<Edge> out = new ArrayList<>();
        for (int k = 0; k < kept.size(); k++) {
            int[] interval = kept.get(k);
            if (interval != null) {
                for (Edge e : edges) {
                    int b = Math.max(e.begin, interval[0]), f = Math.min(e.end, interval[1]);
                    if (b < f) {
                        out.add(new Edge(e.wayId, b, f));
                    }
                }
            }
            if (k < gaps.size()) {
                out.add(Edge.gapMarker(k));
            }
        }
        return out;
    }

    private static int snappedVertex(List<Edge> edges, List<Pt> shape, MatchedPoint m) {
        Edge e = edges.get(m.edgeIndex());
        Pt pos = m.lat() != null ? new Pt(m.lat(), m.lon()) : shape.get(e.begin);
        int best = e.begin;
        double bestD = Double.MAX_VALUE;
        for (int k = e.begin; k <= e.end; k++) {
            double d = haversineM(shape.get(k), pos);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ segments

    public static List<Segment> buildSegments(List<Edge> edges, List<Pt> shape, Map<Long, WayGeom> ways, List<String> warnings) {
        List<Segment> segments = new ArrayList<>();
        boolean afterGap = false;
        for (int n = 0; n < edges.size(); n++) {
            Edge edge = edges.get(n);
            if (edge.isGap()) {
                edge.afterSegment = segments.size() - 1;
                afterGap = true;
                continue;
            }
            if (edge.wayId == 0) {
                continue;
            }
            WayGeom way = ways.get(edge.wayId);
            if (way == null) {
                addOnce(warnings, "matched way " + edge.wayId + " is not in the downloaded data");
                continue;
            }
            Pt p0 = shape.get(edge.begin), p1 = shape.get(edge.end);
            int i0 = nearestIndex(way.geometry(), p0), i1 = nearestIndex(way.geometry(), p1);
            double d = Math.max(haversineM(way.geometry().get(i0), p0), haversineM(way.geometry().get(i1), p1));
            if (d > NODE_SNAP_TOLERANCE_M && n != 0 && n != edges.size() - 1) {
                addOnce(warnings, "way " + edge.wayId + ": geometry differs from the matcher's data (stale routing data? way changed?)");
            }
            if (way.closed()) {
                i0 = 0;
                i1 = way.nodes().length - 1;
            } else if (i0 == i1) {
                continue;
            }
            Segment prev = !segments.isEmpty() && !afterGap ? segments.get(segments.size() - 1) : null;
            afterGap = false;
            if (prev != null && prev.wayId == edge.wayId
                    && (way.closed() || (prev.end == i0 && prev.forward() == (i1 >= i0)))) {
                if (!way.closed()) {
                    prev.end = i1;
                }
                continue;
            }
            segments.add(new Segment(edge.wayId, i0, i1));
        }

        List<Edge> markers = edges.stream().filter(Edge::isGap).toList();
        dropSpursAtGaps(segments, markers);

        boolean gapAtEnds = false;
        for (Edge m : markers) {
            gapAtEnds |= m.afterSegment == -1 || m.afterSegment == segments.size() - 1;
        }
        if (segments.size() > 2 && !gapAtEnds) {
            Segment first = segments.get(0), last = segments.get(segments.size() - 1);
            if (first.wayId == last.wayId && last.end == first.start && first.forward() == last.forward()
                    && !ways.get(first.wayId).closed()) {
                segments.set(0, new Segment(first.wayId, last.start, first.end));
                segments.remove(segments.size() - 1);
            }
        }
        return segments;
    }

    private static void dropSpursAtGaps(List<Segment> segments, List<Edge> markers) {
        for (int n = 0; n < markers.size(); n++) {
            Edge marker = markers.get(n);
            int k = marker.afterSegment, removed = 0;
            while (k + 2 < segments.size() && outAndBack(segments.get(k + 1), segments.get(k + 2))) {
                segments.subList(k + 1, k + 3).clear();
                removed += 2;
            }
            while (k >= 1 && outAndBack(segments.get(k - 1), segments.get(k))) {
                segments.subList(k - 1, k + 1).clear();
                k -= 2;
                removed += 2;
            }
            marker.afterSegment = k;
            for (Edge later : markers.subList(n + 1, markers.size())) {
                later.afterSegment -= removed;
            }
        }
    }

    private static boolean outAndBack(Segment a, Segment b) {
        return a.wayId == b.wayId && a.start == b.end && a.end == b.start;
    }

    /** Node ids where each partly used way must be split, per way (insertion order kept). */
    public static Map<Long, List<Long>> computeCuts(List<Segment> segments, Map<Long, WayGeom> ways) {
        Map<Long, Set<Integer>> idx = new LinkedHashMap<>();
        for (Segment s : segments) {
            WayGeom way = ways.get(s.wayId);
            if (way.closed()) {
                continue;
            }
            for (int i : new int[] {Math.min(s.start, s.end), Math.max(s.start, s.end)}) {
                if (i > 0 && i < way.nodes().length - 1) {
                    idx.computeIfAbsent(s.wayId, k -> new TreeSet<>()).add(i);
                }
            }
        }
        Map<Long, List<Long>> cuts = new LinkedHashMap<>();
        idx.forEach((wid, set) -> cuts.put(wid, set.stream().map(i -> ways.get(wid).nodes()[i]).toList()));
        return cuts;
    }

    // ------------------------------------------------------------------ checks

    /** Returns {matchRatio, lengthRatio, traceLength, matchedLength}. */
    public static double[] quality(List<Pt> trace, List<MatchedPoint> matched, List<Pt> shape) {
        long good = matched.stream().filter(m -> !m.unmatched() && m.distance() <= GOOD_POINT_DISTANCE_M).count();
        double matchRatio = matched.isEmpty() ? 0 : (double) good / matched.size();
        double traceLen = lengthM(trace), matchedLen = lengthM(shape);
        return new double[] {matchRatio, traceLen > 0 ? matchedLen / traceLen : 0, traceLen, matchedLen};
    }

    public static boolean qualityOk(double[] q) {
        return q[0] >= QUALITY_MIN_MATCH_RATIO && q[1] >= QUALITY_MIN_LENGTH_RATIO && q[1] <= QUALITY_MAX_LENGTH_RATIO;
    }

    /** Overlap (share of our ways that the existing route also uses) per existing route id. */
    public static Map<Long, Double> overlaps(Map<Long, Collection<Long>> routeWays, Set<Long> ourWays) {
        Map<Long, Double> out = new HashMap<>();
        routeWays.forEach((rid, ids) -> {
            long shared = ids.stream().distinct().filter(ourWays::contains).count();
            out.put(rid, ourWays.isEmpty() ? 0 : (double) shared / ourWays.size());
        });
        return out;
    }

    private static void addOnce(List<String> list, String msg) {
        if (!list.contains(msg)) {
            list.add(msg);
        }
    }
}
