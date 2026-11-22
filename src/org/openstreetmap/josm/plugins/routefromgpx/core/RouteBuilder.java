// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Edge;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Gap;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.GapRun;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.MatchData;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Segment;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.WayGeom;

/** Builds the route relation in a JOSM data set: plan (read only), then apply (split + add). */
public final class RouteBuilder {

    /** What will be done, computed without touching the data. */
    public static final class Plan {
        public final List<Segment> segments;
        public final Map<Long, List<Long>> cuts;
        public final List<Gap> gaps;
        public final List<String> warnings;
        public final double[] quality;
        public final Map<Relation, Double> duplicates;
        final Map<Long, WayGeom> ways;

        Plan(List<Segment> segments, Map<Long, List<Long>> cuts, List<Gap> gaps, List<String> warnings,
             double[] quality, Map<Relation, Double> duplicates, Map<Long, WayGeom> ways) {
            this.segments = segments;
            this.cuts = cuts;
            this.gaps = gaps;
            this.warnings = warnings;
            this.quality = quality;
            this.duplicates = duplicates;
            this.ways = ways;
        }
    }

    /** Result of apply: the new relation, and the member index after which each gap sits. */
    public record Applied(Relation relation, int splitWays, List<Integer> gapAfterMember) {
    }

    private RouteBuilder() {
    }

    static Way way(DataSet ds, long id) {
        return (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
    }

    /** Map-matches the trace on the ways of the data set this kind of route can use (no server). */
    public static MatchData matchLocally(DataSet ds, List<Pt> trace, RouteProfile profile) {
        List<WayGeom> ways = new ArrayList<>();
        ds.getReadLock().lock();
        try {
            for (Way w : ds.getWays()) {
                if (profile.usable(w)) {
                    ways.add(geom(w));
                }
            }
        } finally {
            ds.getReadLock().unlock();
        }
        return new LocalMatcher(ways, trace).match(trace);
    }

    static WayGeom geom(Way w) {
        long[] nodes = new long[w.getNodesCount()];
        List<Pt> geom = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) {
            Node n = w.getNode(i);
            nodes[i] = n.getUniqueId();
            geom.add(new Pt(n.lat(), n.lon()));
        }
        return new WayGeom(w.getUniqueId(), nodes, geom);
    }

    public static Plan plan(DataSet ds, List<Pt> sentPoints, MatchData r, String name, String routeType) {
        List<String> warnings = new ArrayList<>();
        Map<Long, WayGeom> ways = new HashMap<>();
        for (Edge e : r.edges()) {
            if (e.wayId == 0 || ways.containsKey(e.wayId)) {
                continue;
            }
            Way w = way(ds, e.wayId);
            if (w == null || w.isDeleted() || w.isIncomplete() || w.getNodesCount() < 2) {
                continue;   // reported by buildSegments
            }
            ways.put(e.wayId, geom(w));
        }

        List<GapRun> runs = RoutePlanner.addBreaks(RoutePlanner.findGaps(sentPoints, r.matched()), r.breaks());
        List<Edge> edges = runs.isEmpty() ? r.edges() : RoutePlanner.cutOutGaps(r.edges(), r.shape(), r.matched(), runs);
        List<Segment> segments = RoutePlanner.buildSegments(edges, r.shape(), ways, warnings);
        Map<Long, List<Long>> cuts = RoutePlanner.computeCuts(segments, ways);

        List<Gap> gaps = new ArrayList<>();
        for (Edge m : edges) {
            if (m.isGap()) {
                GapRun g = runs.get(m.gap);
                int lo = g.before() < 0 ? g.start() : g.before();
                int hi = g.after() < 0 ? g.end() : g.after();
                List<Pt> track = new ArrayList<>(sentPoints.subList(lo, hi + 1));
                gaps.add(new Gap(m.afterSegment, track, RoutePlanner.lengthM(track)));
            }
        }

        // possible duplicates: same name, or most of our ways already in an existing route
        Set<Long> ours = new LinkedHashSet<>();
        segments.forEach(s -> ours.add(s.wayId));
        Map<Long, Collection<Long>> routeWays = new HashMap<>();
        Map<Long, Relation> byId = new HashMap<>();
        for (Relation rel : ds.getRelations()) {
            if (rel.isDeleted() || !"route".equals(rel.get("type")) || rel.get("route") == null
                    || !rel.get("route").matches(RouteProfile.of(routeType).related())) {
                continue;
            }
            List<Long> ids = new ArrayList<>();
            for (RelationMember m : rel.getMembers()) {
                if (m.isWay()) {
                    ids.add(m.getUniqueId());
                }
            }
            routeWays.put(rel.getUniqueId(), ids);
            byId.put(rel.getUniqueId(), rel);
        }
        Map<Relation, Double> duplicates = new LinkedHashMap<>();
        RoutePlanner.overlaps(routeWays, ours).forEach((rid, overlap) -> {
            Relation rel = byId.get(rid);
            if (overlap >= RoutePlanner.DUPLICATE_MIN_OVERLAP || (name != null && name.equalsIgnoreCase(rel.get("name")))) {
                duplicates.put(rel, overlap);
            }
        });

        double[] quality = RoutePlanner.quality(sentPoints, r.matched(), r.shape());
        return new Plan(segments, cuts, gaps, warnings, quality, duplicates, ways);
    }

    /**
     * Split the partly used ways (JOSM keeps other relations consistent), then add the relation.
     * Each step goes through the undo stack.
     */
    public static Applied apply(DataSet ds, Plan plan, Map<String, String> tags, List<String> warnings) {
        Map<Long, List<Way>> chunks = new HashMap<>();
        int split = 0;
        for (Map.Entry<Long, List<Long>> cut : plan.cuts.entrySet()) {
            Way w = way(ds, cut.getKey());
            List<Node> at = new ArrayList<>();
            for (long nid : cut.getValue()) {
                at.add((Node) ds.getPrimitiveById(new SimplePrimitiveId(nid, OsmPrimitiveType.NODE)));
            }
            SplitWayCommand cmd = null;
            try {
                cmd = SplitWayCommand.split(w, at, Collections.emptyList());
            } catch (RuntimeException e) {
                warnings.add("could not split way " + w.getUniqueId() + ": " + e.getMessage());
            }
            if (cmd == null) {
                continue;
            }
            UndoRedoHandler.getInstance().add(cmd);
            List<Way> parts = new ArrayList<>(cmd.getNewWays());
            if (!parts.contains(cmd.getOriginalWay())) {
                parts.add(cmd.getOriginalWay());
            }
            chunks.put(cut.getKey(), parts);
            split++;
        }

        List<RelationMember> members = new ArrayList<>();
        List<Integer> segToLastMember = new ArrayList<>();
        for (Segment s : plan.segments) {
            for (Way w : waysFor(ds, s, plan.ways.get(s.wayId), chunks.get(s.wayId), warnings)) {
                members.add(new RelationMember("", w));
            }
            segToLastMember.add(members.size() - 1);
        }
        List<Integer> gapAfter = new ArrayList<>();
        for (Gap g : plan.gaps) {
            gapAfter.add(g.afterSegment() < 0 ? -1 : segToLastMember.get(g.afterSegment()));
        }

        Relation rel = new Relation();
        rel.setKeys(tags);
        rel.setMembers(members);
        UndoRedoHandler.getInstance().add(new AddCommand(ds, rel));
        return new Applied(rel, split, gapAfter);
    }

    /** The way(s) covering a segment, in travel order, after its way was split. */
    static List<Way> waysFor(DataSet ds, Segment s, WayGeom geom, List<Way> parts, List<String> warnings) {
        if (parts == null) {
            return List.of(way(ds, s.wayId));
        }
        // node ids of the segment, in travel order
        List<Long> seq = new ArrayList<>();
        int step = s.forward() ? 1 : -1;
        for (int i = s.start; ; i += step) {
            seq.add(geom.nodes()[i]);
            if (i == s.end) {
                break;
            }
        }
        Map<Long, Integer> pos = new HashMap<>();
        for (int i = seq.size() - 1; i >= 0; i--) {
            pos.put(seq.get(i), i);
        }
        List<Way> out = new ArrayList<>();
        int cur = 0;
        while (cur < seq.size() - 1) {
            Way next = null;
            int nextPos = -1;
            for (Way p : parts) {
                long a = p.firstNode().getUniqueId(), b = p.lastNode().getUniqueId();
                long here = seq.get(cur);
                long other = a == here ? b : (b == here ? a : Long.MIN_VALUE);
                Integer op = pos.get(other);
                if (op != null && op > cur && p.getNodesCount() == op - cur + 1) {
                    next = p;
                    nextPos = op;
                    break;
                }
            }
            if (next == null) {
                warnings.add("way " + s.wayId + ": could not find the split part between nodes "
                        + seq.get(cur) + " and " + seq.get(seq.size() - 1) + "; check this member");
                out.add(way(ds, s.wayId));
                break;
            }
            out.add(next);
            cur = nextPos;
        }
        return out;
    }
}
