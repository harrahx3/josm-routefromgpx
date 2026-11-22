// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;

/**
 * One track to turn into a route relation: a {@code <trk>} (its segments joined) or a
 * {@code <rte>} of a GPX file or layer, with the tags specific to this route.
 */
public final class TrackSource {

    /** GPX file name without extension, or the layer name. */
    public final String file;
    /** Name of the folder holding the file ("" for a layer). */
    public final String folder;
    /** {@code <name>} of the track ("" if none). */
    public final String trackName;
    /** {@code <desc>} of the track ("" if none). */
    public final String desc;
    /** 1-based position of the track in its file. */
    public final int number;
    /** True if the file holds more than one track (then the label shows the track). */
    public final boolean multi;
    /** True if the track comes from a file not open as a layer (it is shown in a layer after the run). */
    public final boolean fromFile;
    public final List<Pt> points;

    /** Tags specific to this route; a blank value means "use the common value". */
    public final Map<String, String> tags = new LinkedHashMap<>();
    public boolean enabled = true;
    /** Result of the last run, shown in the table ("" before). */
    public String status = "";
    /** Length and elevation gain of the track (for {distance}, {ascent}, {descent}). */
    public TrackStats stats = TrackStats.NONE;

    public TrackSource(String file, String folder, String trackName, String desc, int number, boolean multi,
            boolean fromFile, List<Pt> points) {
        this.file = file;
        this.folder = folder;
        this.trackName = trackName == null ? "" : trackName.trim();
        this.desc = desc == null ? "" : desc.trim();
        this.number = number;
        this.multi = multi;
        this.fromFile = fromFile;
        this.points = points;
    }

    /** Default route name: the track name, else the file name. */
    public String defaultName() {
        return trackName.isEmpty() ? file : trackName;
    }

    /** Label shown in the table: the file, plus the track when the file has several. */
    public String label() {
        return multi ? file + " #" + number + (trackName.isEmpty() ? "" : " " + trackName) : file;
    }

    /**
     * Values of the placeholders that tag values may use: {file}, {folder}, {track}, {desc}, {n},
     * {distance} (km), {ascent} and {descent} (m, empty if the track has no elevation).
     */
    public Map<String, String> variables() {
        return Map.of("file", file, "folder", folder, "track", trackName, "desc", desc, "n", String.valueOf(number),
                "distance", stats.distanceTag(), "ascent", stats.ascentTag(), "descent", stats.descentTag());
    }
}
