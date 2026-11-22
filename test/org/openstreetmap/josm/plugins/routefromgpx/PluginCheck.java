// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.data.preferences.JosmUrls;

import org.openstreetmap.josm.plugins.routefromgpx.core.RouteBuilder;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.plugins.routefromgpx.core.RouteProfile;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Headless checks of the plugin logic: tag templates, CSV, GPX reading, and the whole pipeline
 * (match, plan, split, relation) on a small synthetic data set. Run by {@code ./build.sh test}.
 */
public final class PluginCheck {
    private static int failures;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());          // in memory, never saved
        Config.setUrlsProvider(JosmUrls.getInstance());            // needed by the GPX reader
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        templates();
        csv();
        gpx();
        pipeline();
        System.out.println(failures == 0 ? "PluginCheck: all checks passed" : failures + " check(s) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void templates() {
        Map<String, String> vars = map("file", "Boucle_12", "folder", "Doubs", "track", "Boucle des crêtes", "desc", "", "n", "1");
        Map<String, String> tags = TagTemplate.merge(
                map("route", "hiking", "network", "lwn", "operator", "CD {folder}", "ref", "{file}", "description", "{desc}"),
                map("name", "Boucle des crêtes", "network", "rwn", "ref", " "), vars);
        check("template: type=route first", "type".equals(tags.keySet().iterator().next()) && "route".equals(tags.get("type")));
        check("template: specific value wins", "rwn".equals(tags.get("network")));
        check("template: blank specific value keeps the common one", "Boucle_12".equals(tags.get("ref")));
        check("template: placeholder", "CD Doubs".equals(tags.get("operator")));
        check("template: tag with an empty placeholder is dropped", !tags.containsKey("description"));
        check("template: no problems", TagTemplate.problems(tags).isEmpty());
        check("template: missing route", TagTemplate.problems(map("type", "route", "name", "x")).size() == 1);
        check("template: unknown placeholder reported",
                TagTemplate.problems(TagTemplate.merge(map("route", "hiking", "ref", "{nope}"), map(), vars)).size() == 1);
        check("template: prefs round trip", TagTemplate.parse(TagTemplate.format(tags)).equals(tags));
    }

    static void csv() throws Exception {
        String text = "﻿File;Track;ref;name\r\nboucle_1.gpx;;B1;\"Boucle \"\"un\"\"; nord\"\nmulti;2;M2;\nabsent;;X;\n";
        CsvTags.Table t = CsvTags.read(new StringReader(text));
        check("csv: separator ; and BOM", t.header().equals(List.of("file", "track", "ref", "name")));
        check("csv: quoted value", "Boucle \"un\"; nord".equals(t.rows().get(0).get("name")));
        check("csv: keys", t.keys().equals(List.of("ref", "name")));
        List<TrackSource> tracks = new ArrayList<>(List.of(
                new TrackSource("Boucle_1", "", "", "", 1, false, true, List.of()),
                new TrackSource("multi", "", "a", "", 1, true, true, List.of()),
                new TrackSource("multi", "", "b", "", 2, true, true, List.of())));
        List<Map<String, String>> unmatched = CsvTags.apply(t, tracks);
        check("csv: file matched without .gpx, case-insensitive", "B1".equals(tracks.get(0).tags.get("ref")));
        check("csv: track number", "M2".equals(tracks.get(2).tags.get("ref")) && !tracks.get(1).tags.containsKey("ref"));
        check("csv: blank cell not copied", !tracks.get(2).tags.containsKey("name"));
        check("csv: unmatched row reported", unmatched.size() == 1 && "absent".equals(unmatched.get(0).get("file")));
        StringWriter out = new StringWriter();
        CsvTags.write(out, List.of("file", "name"), List.of(List.of("a", "x, \"y\"")));
        CsvTags.Table back = CsvTags.read(new StringReader(out.toString()));
        check("csv: write then read", "x, \"y\"".equals(back.rows().get(0).get("name")));
    }

    static void gpx() throws Exception {
        File dir = Files.createTempDirectory("routefromgpx").toFile();
        File f = new File(dir, "two tracks.gpx");
        Files.writeString(f.toPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><name>Aller</name><desc>Première étape</desc>
                    <trkseg><trkpt lat="45.0" lon="1.0"><ele>100</ele></trkpt><trkpt lat="45.0" lon="1.001"><ele>102</ele></trkpt></trkseg>
                    <trkseg><trkpt lat="45.0" lon="1.001"/><trkpt lat="45.0" lon="1.002"><ele>130</ele></trkpt></trkseg>
                  </trk>
                  <trk><name>Seul point</name><trkseg><trkpt lat="45.0" lon="1.0"/></trkseg></trk>
                  <rte><name>Retour</name><rtept lat="45.0" lon="1.002"/><rtept lat="45.0" lon="1.0"/></rte>
                </gpx>
                """, StandardCharsets.UTF_8);
        Files.writeString(new File(dir, "notes.txt").toPath(), "not a gpx");
        List<TrackSource> t = GpxTracks.read(f);
        check("gpx: one track + one route (1-point track left out)", t.size() == 2);
        check("gpx: segments joined, duplicate point dropped", t.get(0).points.size() == 3);
        // JOSM's GPX reader keeps the <name> of tracks but not of routes (<rte>)
        check("gpx: names and desc", "Aller".equals(t.get(0).trackName) && "Première étape".equals(t.get(0).desc));
        check("gpx: file, folder, label", "two tracks".equals(t.get(0).file) && dir.getName().equals(t.get(0).folder)
                && "two tracks #1 Aller".equals(t.get(0).label()) && "two tracks".equals(t.get(1).defaultName()));
        check("gpx: folder listing", GpxTracks.gpxFiles(dir, false).size() == 1);
        Map<String, String> v = t.get(0).variables();
        check("gpx: {distance} in km", "0.2".equals(v.get("distance")));
        check("gpx: {ascent}/{descent}, small changes ignored", "30".equals(v.get("ascent")) && "0".equals(v.get("descent")));
        Map<String, String> noEle = TagTemplate.merge(map("route", "hiking", "ascent", "{ascent}", "distance", "{distance}"),
                Map.of(), t.get(1).variables());
        check("gpx: no elevation, ascent dropped", !noEle.containsKey("ascent") && "0.2".equals(noEle.get("distance")));
        check("suggestions: route values are the known types", RouteTags.values("route").contains("mtb")
                && RouteTags.values("network").contains("lwn") && RouteTags.values("unknown").isEmpty());
    }

    /**
     * Ways along latitude 45, ~79 m between nodes: way A = nodes 1..5, way B = nodes 5..7; the
     * track goes from node 2 to node 7, so A must be split at node 2 and the relation is
     * [A(2..5), B].
     */
    static void pipeline() {
        DataSet ds = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            Node n = new Node(new LatLon(45, 1 + 0.001 * i));
            ds.addPrimitive(n);
            nodes.add(n);
        }
        Way a = new Way();
        a.setNodes(nodes.subList(0, 5));
        a.put("highway", "path");
        ds.addPrimitive(a);
        Way b = new Way();
        b.setNodes(nodes.subList(4, 7));
        b.put("highway", "track");
        ds.addPrimitive(b);
        ds.addDataSource(new DataSource(new Bounds(44.99, 1.0, 45.01, 1.01), "test"));

        List<Pt> track = new ArrayList<>();
        for (double lon = 1.002; lon <= 1.007 + 1e-9; lon += 0.0001) {
            track.add(new Pt(45 + 0.00002, lon));
        }
        check("pipeline: area already covered", Areas.covered(ds, Areas.bounds(track)));
        List<Pt> sent = RoutePlanner.thin(track);
        RoutePlanner.MatchData match = RouteBuilder.matchLocally(ds, sent, RouteProfile.of("hiking"));
        RouteBuilder.Plan plan = RouteBuilder.plan(ds, sent, match, "Test", "hiking");
        check("pipeline: 2 segments, 1 way to split, no gap", plan.segments.size() == 2 && plan.cuts.size() == 1 && plan.gaps.isEmpty());
        List<String> warnings = new ArrayList<>();
        RouteBuilder.Applied applied = RouteBuilder.apply(ds, plan, map("type", "route", "route", "hiking", "name", "Test"), warnings);
        List<RelationMember> m = applied.relation().getMembers();
        check("pipeline: relation added with its tags", ds.containsRelation(applied.relation())
                && "Test".equals(applied.relation().get("name")));
        check("pipeline: one split", applied.splitWays() == 1 && ds.getWays().size() == 3);
        boolean firstOk = m.size() == 2 && m.get(0).getWay().firstNode() == nodes.get(1) && m.get(0).getWay().lastNode() == nodes.get(4);
        check("pipeline: members [A(2..5), B] in order", firstOk && m.get(1).getWay() == b);
        check("pipeline: no warning " + warnings, warnings.isEmpty());
    }
}
