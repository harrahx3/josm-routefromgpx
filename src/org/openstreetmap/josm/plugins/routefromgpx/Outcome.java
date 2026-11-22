// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Gap;

/** What happened to one track during a run. */
public final class Outcome {

    public enum Status {
        CREATED, DUPLICATE_SKIPPED, NOT_MATCHED, FAILED, CANCELLED;

        public String label() {
            return switch (this) {
                case CREATED -> tr("created");
                case DUPLICATE_SKIPPED -> tr("skipped: similar route exists");
                case NOT_MATCHED -> tr("no ways matched");
                case FAILED -> tr("failed");
                case CANCELLED -> tr("cancelled");
            };
        }
    }

    public final TrackSource source;
    public final Map<String, String> tags;
    public Status status = Status.CANCELLED;
    public String message = "";
    public Relation relation;
    public int splitWays;
    public List<Gap> gaps = List.of();
    /** Member index (0-based, -1 = before the first) after which each gap sits. */
    public List<Integer> gapAfterMember = List.of();
    public double[] quality;
    public final List<String> warnings = new ArrayList<>();
    public final List<Relation> duplicates = new ArrayList<>();

    Outcome(TrackSource source, Map<String, String> tags) {
        this.source = source;
        this.tags = tags;
    }

    public String name() {
        return tags.getOrDefault("name", source.label());
    }

    public boolean qualityOk() {
        return quality != null && RoutePlanner.qualityOk(quality);
    }

    /** Short text for the status column of the track table. */
    public String summary() {
        if (status != Status.CREATED) {
            return message.isEmpty() ? status.label() : status.label() + ": " + message;
        }
        StringBuilder sb = new StringBuilder(status.label());
        if (!gaps.isEmpty()) {
            sb.append(", ").append(tr("{0} gap(s)", gaps.size()));
        }
        if (!qualityOk()) {
            sb.append(", ").append(tr("check"));
        }
        return sb.toString();
    }
}
