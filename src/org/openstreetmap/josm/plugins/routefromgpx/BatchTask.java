// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.BoundingBoxDownloader;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.plugins.routefromgpx.core.RouteBuilder;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.MatchData;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.plugins.routefromgpx.core.RouteProfile;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Creates the relations one track after the other, on the worker thread: download the missing
 * data, match the track on the ways of the data layer, then (in the EDT, before the next track,
 * so that it sees the split ways) split the partly used ways and add the relation.
 */
final class BatchTask extends PleaseWaitRunnable {

    /** A track and the final tags of its relation. */
    record Job(TrackSource source, Map<String, String> tags) {
    }

    private final List<Job> jobs;
    private final Options options;
    private final OsmDataLayer layer;
    private final Consumer<List<Outcome>> onDone;
    private final List<Outcome> outcomes = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile BoundingBoxDownloader downloader;

    BatchTask(List<Job> jobs, Options options, OsmDataLayer layer, Consumer<List<Outcome>> onDone) {
        super(tr("Creating route relations from GPX"), false);
        this.jobs = jobs;
        this.options = options;
        this.layer = layer;
        this.onDone = onDone;
    }

    @Override
    protected void realRun() {
        ProgressMonitor pm = getProgressMonitor();
        pm.setTicksCount(jobs.size());
        int i = 0;
        for (Job job : jobs) {
            Outcome o = new Outcome(job.source(), job.tags());
            outcomes.add(o);
            if (cancelled) {
                continue;
            }
            pm.setCustomText(tr("{0}/{1}: {2}", ++i, jobs.size(), o.name()));
            try {
                run(o, pm);
            } catch (OsmTransferException e) {
                fail(o, cancelled ? Outcome.Status.CANCELLED : Outcome.Status.FAILED, tr("download failed: {0}", e.getMessage()), e);
            } catch (RuntimeException e) {
                fail(o, Outcome.Status.FAILED, e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
            pm.worked(1);
        }
    }

    private static void fail(Outcome o, Outcome.Status status, String message, Exception e) {
        o.status = status;
        o.message = message;
        Logging.warn(e);
    }

    private void run(Outcome o, ProgressMonitor pm) throws OsmTransferException {
        List<Pt> points = o.source.points;
        DataSet ds = layer.getDataSet();
        if (options.download()) {
            double maxArea = Config.getPref().getDouble("osm-server.max-request-area", 0.25);
            double tile = Config.getPref().getDouble(Options.PREF_TILE_AREA, 0.01);
            for (Bounds b : Areas.downloadAreas(points, maxArea, tile)) {
                if (cancelled) {
                    return;
                }
                if (!Areas.covered(ds, b)) {
                    downloader = new BoundingBoxDownloader(b);
                    DataSet downloaded = downloader.parseOsm(pm.createSubTaskMonitor(0, false));
                    downloader = null;
                    if (downloaded != null) {
                        GuiHelper.runInEDTAndWait(() -> layer.mergeFrom(downloaded));
                    }
                }
            }
        }
        if (cancelled) {
            return;
        }
        List<Pt> sent = RoutePlanner.thin(points);
        String route = o.tags.get("route");
        MatchData match = RouteBuilder.matchLocally(ds, sent, RouteProfile.of(route));
        if (match.edges().stream().noneMatch(e -> e.wayId != 0)) {
            o.status = Outcome.Status.NOT_MATCHED;
            o.message = options.download() ? tr("no way usable by route={0} near the track", route)
                    : tr("no way usable by route={0} near the track (is the area downloaded?)", route);
            return;
        }
        GuiHelper.runInEDTAndWait(() -> build(o, ds, sent, match, route));
    }

    /** In the EDT: plan, duplicate check, split and add the relation. */
    private void build(Outcome o, DataSet ds, List<Pt> sent, MatchData match, String route) {
        RouteBuilder.Plan plan = RouteBuilder.plan(ds, sent, match, o.tags.get("name"), route);
        o.quality = plan.quality;
        o.warnings.addAll(plan.warnings);
        if (plan.segments.isEmpty()) {
            o.status = Outcome.Status.NOT_MATCHED;
            o.message = tr("none of the matched ways is in the data layer");
            return;
        }
        o.duplicates.addAll(plan.duplicates.keySet());
        if (!plan.duplicates.isEmpty()) {
            StringBuilder names = new StringBuilder();
            plan.duplicates.forEach((rel, overlap) -> names.append("\n - ")
                    .append(rel.getDisplayName(DefaultNameFormatter.getInstance()))
                    .append(" (").append(tr("{0}% of the ways", Math.round(overlap * 100))).append(')'));
            boolean create = switch (options.onDuplicate()) {
                case CREATE -> true;
                case SKIP -> false;
                case ASK -> JOptionPane.showConfirmDialog(MainApplication.getMainFrame(),
                        tr("\"{0}\" may already exist:{1}\n\nCreate the relation anyway?", o.name(), names),
                        tr("Route relations from GPX"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                        == JOptionPane.YES_OPTION;
            };
            if (!create) {
                o.status = Outcome.Status.DUPLICATE_SKIPPED;
                o.message = names.toString().replace("\n - ", " ").trim();
                return;
            }
            o.warnings.add(tr("similar route(s) already exist:{0}", names.toString().replace("\n - ", " ")));
        }
        RouteBuilder.Applied applied = RouteBuilder.apply(ds, plan, withGapNote(o.tags, plan), o.warnings);
        o.relation = applied.relation();
        o.splitWays = applied.splitWays();
        o.gaps = plan.gaps;
        o.gapAfterMember = applied.gapAfterMember();
        o.status = Outcome.Status.CREATED;
    }

    /** The tags, plus a fixme (appended to an existing one) when gaps were left open. */
    private Map<String, String> withGapNote(Map<String, String> tags, RouteBuilder.Plan plan) {
        if (!options.markGaps() || plan.gaps.isEmpty()) {
            return tags;
        }
        double total = plan.gaps.stream().mapToDouble(RoutePlanner.Gap::lengthM).sum();
        String note = "route has " + plan.gaps.size() + " gap(s), about " + Math.round(total)
                + " m in total, where no way was found: check and connect, or confirm the path";
        Map<String, String> out = new java.util.LinkedHashMap<>(tags);
        out.merge("fixme", note, (old, add) -> old.contains(add) ? old : old + "; " + add);
        return out;
    }

    @Override
    protected void cancel() {
        cancelled = true;
        BoundingBoxDownloader d = downloader;
        if (d != null) {
            d.cancel();
        }
    }

    @Override
    protected void finish() {
        GuiHelper.runInEDT(() -> onDone.accept(outcomes));
    }
}
