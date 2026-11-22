// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Edge;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.MatchData;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.MatchedPoint;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.WayGeom;

/**
 * Map matching on the ways already in the data set, without a routing server: a hidden Markov
 * model (Newson &amp; Krumm 2009) solved with Viterbi. The states of a trace point are its
 * projections on the way segments within {@link #SEARCH_RADIUS_M}; the emission score is
 * Gaussian in the distance to the point; the transition score penalises the difference between
 * the route length (Dijkstra on the ways) and the straight-line distance between the points.
 * <p>
 * The answer ({@link MatchData}: matched shape, edges with their way, matched points) feeds the rest of the pipeline
 * ({@link RoutePlanner#findGaps}, {@link RoutePlanner#cutOutGaps}, {@link RoutePlanner#buildSegments}).
 * The matched path is built from whole node-to-node steps, so GPS jitter around a
 * node cannot create extra members; out-and-back trips shorter than {@link #SPUR_MAX_M}, or
 * shorter than {@link #TURNAROUND_MAX_M} and turning back where the way goes on, are dropped.
 * Where no route links two consecutive points (ways that don't share a node, or a long unmapped
 * stretch), the path breaks and the break is reported in {@link MatchData#breaks()}.
 * <p>
 * Pure Java, no JOSM dependency.
 */
public final class LocalMatcher {

    public static final double SEARCH_RADIUS_M = 50;
    public static final int MAX_CANDIDATES = 8;
    /** Same way, candidates closer than this along it: keep only the nearest. */
    static final double SAME_WAY_MIN_SEPARATION_M = 15;
    /** GPS noise (standard deviation). */
    public static final double SIGMA_M = 10;
    /** Tolerance on |route length - straight-line distance| between consecutive points. */
    public static final double BETA_M = 10;
    /** Shorter out-and-back trips in the matched path are GPS noise. */
    public static final double SPUR_MAX_M = 25;
    /**
     * Shorter out-and-back trips that turn back where the way goes on (not at a dead end) are
     * noise too, or a track that cuts a corner: a real route doesn't turn back mid-way.
     */
    public static final double TURNAROUND_MAX_M = 100;
    static final double ROUTE_LIMIT_FACTOR = 2;
    static final double ROUTE_LIMIT_EXTRA_M = 150;
    static final double CELL_M = 100;
    private static final double R = 6371008.8;

    private record Seg(long wayId, long a, long b, double ax, double ay, double bx, double by, double len) {
    }

    private record Arc(long to, long wayId, double len) {
    }

    private record Cand(int seg, double t, double x, double y, double dist) {
    }

    /** Best way to reach a candidate: previous candidate, and the nodes crossed on the way. */
    private static final class Step {
        double score = Double.NEGATIVE_INFINITY;
        int back = -1;
        long[] nodes = new long[0];   // nodes crossed, in order
        long[] ways = new long[0];    // way used to reach each of them
    }

    /** One node of the matched path. */
    private static final class Entry {
        final long node;
        final long wayFromPrev;   // 0 at the start of a chain
        final int chain;
        final double x, y;
        Entry alias;              // set when removed (spur): the entry that replaces it

        Entry(long node, long wayFromPrev, int chain, double[] xy) {
            this.node = node;
            this.wayFromPrev = wayFromPrev;
            this.chain = chain;
            this.x = xy[0];
            this.y = xy[1];
        }

        Entry resolve() {
            Entry e = this;
            while (e.alias != null) {
                e = e.alias;
            }
            return e;
        }
    }

    private final double lat0, lon0, cos0;
    private final List<Seg> segs = new ArrayList<>();
    private final Map<Long, List<Arc>> adj = new HashMap<>();
    private final Map<Long, double[]> xy = new HashMap<>();
    private final Map<Long, List<Integer>> grid = new HashMap<>();

    /** @param ways the ways that can be walked (filtering by tags is the caller's job) */
    public LocalMatcher(Collection<WayGeom> ways, List<Pt> trace) {
        double la = 0, lo = 0;
        for (Pt p : trace) {
            la += p.lat();
            lo += p.lon();
        }
        lat0 = trace.isEmpty() ? 0 : la / trace.size();
        lon0 = trace.isEmpty() ? 0 : lo / trace.size();
        cos0 = Math.cos(Math.toRadians(lat0));
        for (WayGeom w : ways) {
            long[] n = w.nodes();
            for (int i = 0; i < n.length; i++) {
                xy.putIfAbsent(n[i], project(w.geometry().get(i)));
            }
            for (int i = 0; i + 1 < n.length; i++) {
                if (n[i] == n[i + 1]) {
                    continue;
                }
                double[] a = xy.get(n[i]), b = xy.get(n[i + 1]);
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                int idx = segs.size();
                segs.add(new Seg(w.id(), n[i], n[i + 1], a[0], a[1], b[0], b[1], len));
                adj.computeIfAbsent(n[i], k -> new ArrayList<>()).add(new Arc(n[i + 1], w.id(), len));
                adj.computeIfAbsent(n[i + 1], k -> new ArrayList<>()).add(new Arc(n[i], w.id(), len));
                int cx0 = cell(Math.min(a[0], b[0])), cx1 = cell(Math.max(a[0], b[0]));
                int cy0 = cell(Math.min(a[1], b[1])), cy1 = cell(Math.max(a[1], b[1]));
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cy = cy0; cy <= cy1; cy++) {
                        grid.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(idx);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private double[] project(Pt p) {
        return new double[] {Math.toRadians(p.lon() - lon0) * cos0 * R, Math.toRadians(p.lat() - lat0) * R};
    }

    private Pt unproject(double x, double y) {
        return new Pt(lat0 + Math.toDegrees(y / R), lon0 + Math.toDegrees(x / (R * cos0)));
    }

    private static int cell(double v) {
        return (int) Math.floor(v / CELL_M);
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) ^ (cy & 0xffffffffL);
    }

    private List<Cand> candidates(double[] p) {
        List<Cand> all = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int cx = cell(p[0] - SEARCH_RADIUS_M); cx <= cell(p[0] + SEARCH_RADIUS_M); cx++) {
            for (int cy = cell(p[1] - SEARCH_RADIUS_M); cy <= cell(p[1] + SEARCH_RADIUS_M); cy++) {
                for (int idx : grid.getOrDefault(key(cx, cy), List.of())) {
                    if (!seen.add(idx)) {
                        continue;
                    }
                    Seg s = segs.get(idx);
                    double dx = s.bx - s.ax, dy = s.by - s.ay;
                    double t = s.len == 0 ? 0 : ((p[0] - s.ax) * dx + (p[1] - s.ay) * dy) / (s.len * s.len);
                    t = Math.max(0, Math.min(1, t));
                    double x = s.ax + t * dx, y = s.ay + t * dy;
                    double d = Math.hypot(p[0] - x, p[1] - y);
                    if (d <= SEARCH_RADIUS_M) {
                        all.add(new Cand(idx, t, x, y, d));
                    }
                }
            }
        }
        all.sort((a, b) -> Double.compare(a.dist, b.dist));
        List<Cand> kept = new ArrayList<>();
        for (Cand c : all) {
            boolean dup = false;
            for (Cand k : kept) {
                dup |= segs.get(k.seg).wayId == segs.get(c.seg).wayId
                        && Math.hypot(k.x - c.x, k.y - c.y) < SAME_WAY_MIN_SEPARATION_M;
            }
            if (!dup) {
                kept.add(c);
                if (kept.size() == MAX_CANDIDATES) {
                    break;
                }
            }
        }
        return kept;
    }

    private static double emission(Cand c) {
        return -0.5 * (c.dist / SIGMA_M) * (c.dist / SIGMA_M);
    }

    // ------------------------------------------------------------------ routing

    /** Shortest routes from candidate a to each of the targets (bounded by limit). */
    private Step[] route(Cand a, List<Cand> targets, double limit) {
        Seg sa = segs.get(a.seg);
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prevNode = new HashMap<>(), prevWay = new HashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>((p, q) -> Double.compare((Double) p[0], (Double) q[0]));
        double da = a.t * sa.len, db = (1 - a.t) * sa.len;
        dist.put(sa.a, da);
        pq.add(new Object[] {da, sa.a});
        if (db < dist.getOrDefault(sa.b, Double.MAX_VALUE)) {
            dist.put(sa.b, db);
            pq.add(new Object[] {db, sa.b});
        }
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            double d = (Double) top[0];
            long n = (Long) top[1];
            if (d > dist.get(n) || d > limit) {
                continue;
            }
            for (Arc arc : adj.getOrDefault(n, List.of())) {
                double nd = d + arc.len;
                if (nd <= limit && nd < dist.getOrDefault(arc.to, Double.MAX_VALUE)) {
                    dist.put(arc.to, nd);
                    prevNode.put(arc.to, n);
                    prevWay.put(arc.to, arc.wayId);
                    pq.add(new Object[] {nd, arc.to});
                }
            }
        }

        Step[] out = new Step[targets.size()];
        for (int k = 0; k < targets.size(); k++) {
            Cand b = targets.get(k);
            Seg sb = segs.get(b.seg);
            Step st = new Step();
            out[k] = st;
            if (b.seg == a.seg) {
                st.score = Math.abs(b.t - a.t) * sb.len;   // route length for now
                continue;
            }
            double viaA = dist.getOrDefault(sb.a, Double.MAX_VALUE) + b.t * sb.len;
            double viaB = dist.getOrDefault(sb.b, Double.MAX_VALUE) + (1 - b.t) * sb.len;
            double best = Math.min(viaA, viaB);
            if (best > limit) {
                continue;
            }
            long end = viaA <= viaB ? sb.a : sb.b;
            List<Long> nodes = new ArrayList<>(), ways = new ArrayList<>();
            for (long n = end; ; n = prevNode.get(n)) {
                nodes.add(0, n);
                ways.add(0, prevNode.containsKey(n) ? prevWay.get(n) : sa.wayId);
                if (!prevNode.containsKey(n)) {
                    break;
                }
            }
            st.score = best;
            st.nodes = nodes.stream().mapToLong(Long::longValue).toArray();
            st.ways = ways.stream().mapToLong(Long::longValue).toArray();
        }
        return out;
    }

    // ------------------------------------------------------------------ matching

    public MatchData match(List<Pt> trace) {
        int n = trace.size();
        List<double[]> pts = new ArrayList<>();
        List<List<Cand>> cands = new ArrayList<>();
        for (Pt p : trace) {
            double[] q = project(p);
            pts.add(q);
            cands.add(candidates(q));
        }

        // Viterbi; a chain is a run of points with a continuous matched path
        Step[][] steps = new Step[n][];
        List<int[]> chains = new ArrayList<>();   // {first point, last point}
        int prev = -1;
        for (int i = 0; i < n; i++) {
            List<Cand> cs = cands.get(i);
            if (cs.isEmpty()) {
                continue;
            }
            steps[i] = new Step[cs.size()];
            boolean linked = false;
            if (prev >= 0) {
                double gc = Math.hypot(pts.get(i)[0] - pts.get(prev)[0], pts.get(i)[1] - pts.get(prev)[1]);
                double limit = ROUTE_LIMIT_FACTOR * gc + ROUTE_LIMIT_EXTRA_M;
                for (int k = 0; k < cs.size(); k++) {
                    steps[i][k] = new Step();
                }
                List<Cand> from = cands.get(prev);
                for (int a = 0; a < from.size(); a++) {
                    double base = steps[prev][a].score;
                    if (base == Double.NEGATIVE_INFINITY) {
                        continue;
                    }
                    Step[] r = route(from.get(a), cs, limit);
                    for (int k = 0; k < cs.size(); k++) {
                        if (r[k].score == Double.NEGATIVE_INFINITY) {
                            continue;
                        }
                        double s = base - Math.abs(r[k].score - gc) / BETA_M + emission(cs.get(k));
                        if (s > steps[i][k].score) {
                            r[k].score = s;
                            r[k].back = a;
                            steps[i][k] = r[k];
                        }
                    }
                }
                for (Step st : steps[i]) {
                    linked |= st.score > Double.NEGATIVE_INFINITY;
                }
            }
            if (!linked) {
                if (prev >= 0) {
                    chains.get(chains.size() - 1)[1] = prev;
                }
                chains.add(new int[] {i, i});
                for (int k = 0; k < cs.size(); k++) {
                    steps[i][k] = new Step();
                    steps[i][k].score = emission(cs.get(k));
                }
            }
            prev = i;
        }
        if (prev >= 0) {
            chains.get(chains.size() - 1)[1] = prev;
        }

        // back-track each chain, then build its node path
        int[] chosen = new int[n];
        java.util.Arrays.fill(chosen, -1);
        Entry[] at = new Entry[n];
        List<Entry> path = new ArrayList<>();
        for (int c = 0; c < chains.size(); c++) {
            int first = chains.get(c)[0], last = chains.get(c)[1];
            int best = 0;
            for (int k = 1; k < steps[last].length; k++) {
                if (steps[last][k].score > steps[last][best].score) {
                    best = k;
                }
            }
            for (int i = last, k = best; ; ) {
                chosen[i] = k;
                if (i == first) {
                    break;
                }
                int b = steps[i][k].back;
                do {
                    i--;
                } while (steps[i] == null);
                k = b;
            }
            Cand c0 = cands.get(first).get(chosen[first]);
            Seg s0 = segs.get(c0.seg);
            path.add(new Entry(c0.t < 0.5 ? s0.a : s0.b, 0, c, xy.get(c0.t < 0.5 ? s0.a : s0.b)));
            // Only nodes crossed between segments are in the steps. A trace that goes more than
            // halfway along a segment and leaves it by the node it came in by turned back at the
            // far node (the tip of a spur): add that node, as the nearest-node rounding would.
            Cand cur = c0;
            double reach = 0;   // how far along cur's segment from the node we entered by (0..1)
            for (int i = first; i <= last; i++) {
                if (chosen[i] < 0) {
                    continue;
                }
                Cand next = cands.get(i).get(chosen[i]);
                Step st = steps[i][chosen[i]];
                if (i != first && st.nodes.length > 0) {
                    turnBack(path, cur, reach, st.nodes[0], c);
                    for (int j = 0; j < st.nodes.length; j++) {
                        append(path, st.nodes[j], st.ways[j], c);
                    }
                    reach = 0;
                }
                cur = next;
                reach = Math.max(reach, along(cur, path.get(path.size() - 1).node));
                at[i] = path.get(path.size() - 1);
            }
            Seg sl = segs.get(cur.seg);
            long exit = cur.t < 0.5 ? sl.a : sl.b;
            turnBack(path, cur, reach, exit, c);
            append(path, exit, sl.wayId, c);
        }

        removeSpurs(path);
        List<Entry> seq = new ArrayList<>();
        for (Entry e : path) {
            if (e.alias == null) {
                seq.add(e);
            }
        }
        Map<Entry, Integer> index = new IdentityHashMap<>();
        List<Pt> shape = new ArrayList<>();
        for (Entry e : seq) {
            index.put(e, shape.size());
            shape.add(unproject(e.x, e.y));
        }
        List<Edge> edges = new ArrayList<>();
        int[] edgeEndingAt = new int[seq.size()], edgeStartingAt = new int[seq.size()];
        java.util.Arrays.fill(edgeEndingAt, -1);
        java.util.Arrays.fill(edgeStartingAt, -1);
        for (int k = 1; k < seq.size(); k++) {
            if (seq.get(k).chain == seq.get(k - 1).chain) {
                edgeEndingAt[k] = edgeStartingAt[k - 1] = edges.size();
                edges.add(new Edge(seq.get(k).wayFromPrev, k - 1, k));
            }
        }

        List<MatchedPoint> matched = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int e = -1;
            if (at[i] != null) {
                int k = index.get(at[i].resolve());
                e = edgeStartingAt[k] >= 0 ? edgeStartingAt[k] : edgeEndingAt[k];
            }
            if (e < 0) {
                matched.add(new MatchedPoint("unmatched", 0, -1, null, null));
            } else {
                Cand c = cands.get(i).get(chosen[i]);
                Pt p = unproject(c.x, c.y);
                matched.add(new MatchedPoint("matched", c.dist, e, p.lat(), p.lon()));
            }
        }
        List<int[]> breaks = new ArrayList<>();
        for (int c = 1; c < chains.size(); c++) {
            breaks.add(new int[] {chains.get(c - 1)[1], chains.get(c)[0]});
        }
        return new MatchData(shape, edges, matched, breaks);
    }

    /** Position of c along its segment, from the given end node (0) to the other one (1). */
    private double along(Cand c, long from) {
        return segs.get(c.seg).a == from ? c.t : 1 - c.t;
    }

    /** Leaving c's segment by exit: if that is where we came in and we got past halfway, add the far node first. */
    private void turnBack(List<Entry> path, Cand c, double reach, long exit, int chain) {
        Seg s = segs.get(c.seg);
        long entry = path.get(path.size() - 1).node;
        if (exit == entry && reach > 0.5) {
            append(path, s.a == entry ? s.b : s.a, s.wayId, chain);
        }
    }

    private void append(List<Entry> path, long node, long way, int chain) {
        if (path.get(path.size() - 1).node != node) {
            path.add(new Entry(node, way, chain, xy.get(node)));
        }
    }

    /** Remove out-and-back trips (A..X..A) that are noise, see SPUR_MAX_M and TURNAROUND_MAX_M. */
    private void removeSpurs(List<Entry> all) {
        List<Entry> p = new ArrayList<>(all);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i + 1 < p.size() && !changed; i++) {
                if (p.get(i - 1).node != p.get(i + 1).node || p.get(i - 1).chain != p.get(i + 1).chain) {
                    continue;
                }
                int k = 0;
                double len = dist(p.get(i - 1), p.get(i));
                while (i - 2 - k >= 0 && i + 2 + k < p.size() && p.get(i - 2 - k).node == p.get(i + 2 + k).node
                        && p.get(i - 2 - k).chain == p.get(i + 2 + k).chain) {
                    len += dist(p.get(i - 2 - k), p.get(i - 1 - k));
                    k++;
                }
                if (len <= SPUR_MAX_M || len <= TURNAROUND_MAX_M && !deadEnd(p.get(i).node)) {
                    Entry base = p.get(i - k - 1);
                    List<Entry> gone = p.subList(i - k, i + k + 2);
                    gone.forEach(e -> e.alias = base);
                    gone.clear();
                    changed = true;
                }
            }
        }
    }

    private boolean deadEnd(long node) {
        return adj.getOrDefault(node, List.of()).stream().map(Arc::to).distinct().count() <= 1;
    }

    private static double dist(Entry a, Entry b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
