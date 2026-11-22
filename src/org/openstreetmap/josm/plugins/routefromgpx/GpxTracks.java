// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.openstreetmap.josm.data.gpx.GpxConstants;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.data.gpx.GpxRoute;
import org.openstreetmap.josm.data.gpx.IGpxTrack;
import org.openstreetmap.josm.data.gpx.IGpxTrackSegment;
import org.openstreetmap.josm.data.gpx.WayPoint;
import org.openstreetmap.josm.io.GpxReader;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.xml.sax.SAXException;

/** Reads the tracks of GPX files, folders and layers. */
public final class GpxTracks {

    private GpxTracks() {
    }

    /** The .gpx files of a folder, sorted by name; with {@code recursive}, also those of its sub-folders. */
    public static List<File> gpxFiles(File dir, boolean recursive) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            return out;
        }
        Arrays.sort(children, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File f : children) {
            if (f.isDirectory() && recursive && !f.getName().startsWith(".")) {
                out.addAll(gpxFiles(f, true));
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".gpx")) {
                out.add(f);
            }
        }
        return out;
    }

    public static List<TrackSource> read(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            GpxReader reader = new GpxReader(in);
            reader.parse(true);
            File parent = file.getAbsoluteFile().getParentFile();
            return tracks(reader.getGpxData(), stem(file.getName()), parent == null ? "" : parent.getName(), true);
        } catch (SAXException e) {
            throw new IOException(file.getName() + ": " + e.getMessage(), e);
        }
    }

    static String stem(String name) {
        return name.replaceAll("(?i)\\.gpx$", "").trim();
    }

    /**
     * One {@link TrackSource} per track (segments joined in order) and per route; tracks with
     * fewer than 2 points are left out. (JOSM's GPX reader drops the name of routes, so a
     * {@code <rte>} is named after its file.)
     */
    public static List<TrackSource> tracks(GpxData data, String file, String folder, boolean fromFile) {
        record Raw(String name, String desc, List<Pt> points, List<Double> ele) {
        }
        List<Raw> raw = new ArrayList<>();
        for (IGpxTrack t : data.getOrderedTracks()) {
            List<Pt> pts = new ArrayList<>();
            List<Double> ele = new ArrayList<>();
            for (IGpxTrackSegment s : t.getSegments()) {
                for (WayPoint w : s.getWayPoints()) {
                    add(pts, ele, w);
                }
            }
            raw.add(new Raw(t.getString("name"), t.getString("desc"), pts, ele));
        }
        for (GpxRoute r : data.routes) {
            List<Pt> pts = new ArrayList<>();
            List<Double> ele = new ArrayList<>();
            r.routePoints.forEach(w -> add(pts, ele, w));
            raw.add(new Raw(r.getString("name"), r.getString("desc"), pts, ele));
        }
        raw.removeIf(r -> r.points().size() < 2);
        List<TrackSource> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            TrackSource t = new TrackSource(file, folder, r.name(), r.desc(), i + 1, raw.size() > 1, fromFile, r.points());
            t.stats = TrackStats.of(r.points(), r.ele().stream().mapToDouble(Double::doubleValue).toArray());
            out.add(t);
        }
        return out;
    }

    private static void add(List<Pt> pts, List<Double> ele, WayPoint w) {
        Pt p = new Pt(w.lat(), w.lon());
        if (!Double.isNaN(p.lat()) && (pts.isEmpty() || !pts.get(pts.size() - 1).equals(p))) {
            pts.add(p);
            ele.add(elevation(w));
        }
    }

    /** The {@code <ele>} of a point, NaN if it has none. */
    private static double elevation(WayPoint w) {
        Object e = w.get(GpxConstants.PT_ELE);
        if (e instanceof Number n) {
            return n.doubleValue();
        }
        if (e != null) {
            try {
                return Double.parseDouble(e.toString().trim());
            } catch (NumberFormatException ex) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}
