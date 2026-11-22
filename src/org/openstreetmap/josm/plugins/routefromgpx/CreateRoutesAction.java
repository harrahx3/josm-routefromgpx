// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.data.gpx.GpxTrack;
import org.openstreetmap.josm.data.gpx.WayPoint;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.dialogs.relation.RelationEditor;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Gap;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Pt;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Tools &gt; "Route relations from GPX...": opens {@link RoutesDialog}, then runs
 * {@link BatchTask} and shows {@link ReportDialog}. The list of tracks and their tags is kept
 * for the JOSM session, so that the dialog can be reopened to fix and rerun some tracks.
 */
public class CreateRoutesAction extends JosmAction {

    private final List<TrackSource> tracks = new ArrayList<>();
    private final List<String> keys = new ArrayList<>(Options.loadColumns());

    public CreateRoutesAction() {
        super(tr("Route relations from GPX..."), "routefromgpx",
                tr("Create route relations (hiking, bicycle...) from GPX tracks, files or folders"),
                Shortcut.registerShortcut("tools:routefromgpx", tr("Tools: {0}", tr("Route relations from GPX")),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE), true, "routefromgpx", false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        TrackTableModel model = new TrackTableModel(tracks, keys);
        if (tracks.isEmpty()) {
            addActiveGpxLayer(model);
        }
        RoutesDialog dlg = new RoutesDialog(model);
        dlg.selectFirst();
        if (dlg.showDialog().getValue() != 1) {
            return;
        }
        Map<String, String> common = dlg.commonTags();
        Options options = dlg.options();
        List<BatchTask.Job> jobs = new ArrayList<>();
        for (TrackSource t : model.enabled()) {
            jobs.add(new BatchTask.Job(t, RoutesDialog.finalTags(common, t)));
        }

        OsmDataLayer layer = MainApplication.getLayerManager().getEditLayer();
        if (layer == null) {
            layer = new OsmDataLayer(new DataSet(), tr("Routes from GPX"), null);
            MainApplication.getLayerManager().addLayer(layer);
        }
        if (layer.getDataSet().isLocked()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), tr("The data layer \"{0}\" is locked.", layer.getName()),
                    tr("Route relations from GPX"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        MainApplication.getLayerManager().setActiveLayer(layer);
        OsmDataLayer target = layer;
        MainApplication.worker.submit(new BatchTask(jobs, options, target, outcomes -> done(outcomes, options, target)));
    }

    private static void addActiveGpxLayer(TrackTableModel model) {
        Layer active = MainApplication.getLayerManager().getActiveLayer();
        if (active instanceof GpxLayer g) {
            model.add(GpxTracks.tracks(g.data, GpxTracks.stem(g.getName()), "", false));
        }
    }

    /** In the EDT, after the run: status column, layers with the tracks and the gaps, report. */
    private static void done(List<Outcome> outcomes, Options options, OsmDataLayer layer) {
        for (Outcome o : outcomes) {
            o.source.status = o.summary();
            if (o.status == Outcome.Status.CREATED) {
                o.source.enabled = false;   // done: unchecked, so that a rerun only does the others
            }
        }
        if (options.showTracks()) {
            GpxData tracks = new GpxData();
            for (Outcome o : outcomes) {
                if (o.source.fromFile && o.status != Outcome.Status.CANCELLED) {
                    tracks.addTrack(track(o.source.points, o.name()));
                }
            }
            if (!tracks.isEmpty()) {
                MainApplication.getLayerManager().addLayer(new GpxLayer(tracks, tr("Route tracks")));
            }
        }
        GpxData gaps = new GpxData();
        for (Outcome o : outcomes) {
            int k = 1;
            for (Gap g : o.gaps) {
                gaps.addTrack(track(g.track(), tr("{0}: gap {1} ({2} m)", o.name(), k++, Math.round(g.lengthM()))));
            }
        }
        if (!gaps.isEmpty()) {
            MainApplication.getLayerManager().addLayer(new GpxLayer(gaps, tr("Gaps")));
        }
        MainApplication.getLayerManager().setActiveLayer(layer);

        List<Outcome> created = outcomes.stream().filter(o -> o.relation != null).toList();
        if (!created.isEmpty()) {
            layer.getDataSet().setSelected(created.stream().map(o -> o.relation).toList());
        }
        new ReportDialog(outcomes, layer).setVisible(true);
        if (options.openEditor() && outcomes.size() == 1 && created.size() == 1) {
            RelationEditor.getEditor(layer, created.get(0).relation, null).setVisible(true);
        }
    }

    private static GpxTrack track(List<Pt> points, String name) {
        List<WayPoint> wps = new ArrayList<>();
        points.forEach(p -> wps.add(new WayPoint(new LatLon(p.lat(), p.lon()))));
        Collection<Collection<WayPoint>> segs = List.of(wps);
        return new GpxTrack(segs, Map.of("name", name));
    }
}
