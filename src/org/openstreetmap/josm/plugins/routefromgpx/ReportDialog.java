// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.dialogs.relation.RelationEditor;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.WindowGeometry;
import org.openstreetmap.josm.plugins.routefromgpx.core.RoutePlanner.Gap;
import org.openstreetmap.josm.tools.Utils;

/**
 * Results of a run, in a non-modal window: one row per track. Selecting a row selects the
 * relation (or the similar existing routes) and zooms to it; the details show the warnings and
 * the gaps (double-click a gap line... or use "Zoom to gaps").
 */
final class ReportDialog extends JDialog {

    private final List<Outcome> outcomes;
    private final OsmDataLayer layer;
    private final JTable table;
    private final JTextPane details = new JTextPane();

    ReportDialog(List<Outcome> outcomes, OsmDataLayer layer) {
        super(MainApplication.getMainFrame(), tr("Route relations from GPX: results"), false);
        this.outcomes = outcomes;
        this.layer = layer;
        table = new JTable(new Model());
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
                zoom(false);
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openEditor();
                }
            }
        });
        details.setEditable(false);
        details.setContentType("text/html");

        long created = outcomes.stream().filter(o -> o.status == Outcome.Status.CREATED).count();
        long gaps = outcomes.stream().filter(o -> !o.gaps.isEmpty()).count();
        long check = outcomes.stream().filter(o -> o.status == Outcome.Status.CREATED && !o.qualityOk()).count();
        JLabel head = new JLabel(tr("{0} of {1} relation(s) created; {2} with gaps, {3} to check. "
                + "Nothing is uploaded: review the relations, then upload.", created, outcomes.size(), gaps, check));
        head.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.6);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(new JButton(action(tr("Zoom to"), () -> zoom(true))));
        buttons.add(new JButton(action(tr("Zoom to gaps"), this::zoomToGaps)));
        buttons.add(new JButton(action(tr("Relation editor"), this::openEditor)));
        buttons.add(new JButton(action(tr("Close"), this::dispose)));

        JPanel content = new JPanel(new BorderLayout());
        content.add(head, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        new WindowGeometry(Options.PREFIX + "report.geometry",
                WindowGeometry.centerInWindow(MainApplication.getMainFrame(), new Dimension(900, 520))).applySafe(this);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                dispose();
            }
        });
        if (!outcomes.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    @Override
    public void dispose() {
        new WindowGeometry(this).remember(Options.PREFIX + "report.geometry");
        super.dispose();
    }

    private static AbstractAction action(String label, Runnable r) {
        return new AbstractAction(label) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private Outcome selected() {
        int view = table.getSelectedRow();
        return view < 0 ? null : outcomes.get(table.convertRowIndexToModel(view));
    }

    private void showDetails() {
        Outcome o = selected();
        if (o == null) {
            details.setText("");
            return;
        }
        StringBuilder h = new StringBuilder("<html><body><b>").append(esc(o.name())).append("</b> (")
                .append(esc(o.source.label())).append(")<br>").append(esc(o.summary())).append("<br>");
        if (o.quality != null) {
            double[] q = o.quality;
            h.append(esc(tr("Quality: {0} (matched {1}% of points, length {2} m vs track {3} m)",
                    o.qualityOk() ? tr("ok") : tr("check"), Math.round(q[0] * 100), Math.round(q[3]), Math.round(q[2]))))
                    .append("<br>");
        }
        if (o.relation != null) {
            h.append(esc(tr("{0} members, {1} way(s) split", o.relation.getMembersCount(), o.splitWays))).append("<br>");
        }
        for (String w : o.warnings) {
            h.append("<font color=#b06000>").append(esc(w)).append("</font><br>");
        }
        if (!o.gaps.isEmpty()) {
            h.append("<br><b>").append(esc(tr("{0} gap(s): track with no way under it (path missing from OSM?)", o.gaps.size())))
                    .append("</b><br>").append(esc(tr("They are in the \"Gaps\" layer. If the path exists, draw it along the track, "
                    + "connect both ends and add it to the relation at that position."))).append("<br>");
            for (int i = 0; i < o.gaps.size(); i++) {
                int after = o.gapAfterMember.get(i);
                h.append(esc(tr("gap {0}: {1} m, after member {2}", i + 1, Math.round(o.gaps.get(i).lengthM()),
                        after < 0 ? tr("(start)") : String.valueOf(after + 1)))).append("<br>");
            }
        }
        h.append("</body></html>");
        details.setText(h.toString());
        details.setCaretPosition(0);
    }

    private static String esc(String s) {
        return Utils.escapeReservedCharactersHTML(s);
    }

    private void zoom(boolean always) {
        Outcome o = selected();
        if (o == null || MainApplication.getMap() == null) {
            return;
        }
        DataSet ds = layer.getDataSet();
        if (o.relation != null && o.relation.getDataSet() == ds) {
            ds.setSelected(o.relation);
            zoomTo(o.relation.getBBox());
        } else if (!o.duplicates.isEmpty()) {
            ds.setSelected(o.duplicates);
            zoomTo(o.duplicates.get(0).getBBox());
        } else if (always) {
            MainApplication.getMap().mapView.zoomTo(Areas.bounds(o.source.points));
        }
    }

    private static void zoomTo(BBox b) {
        if (b != null && b.isValid()) {
            MainApplication.getMap().mapView.zoomTo(new Bounds(b.getBottomRightLat(), b.getTopLeftLon(),
                    b.getTopLeftLat(), b.getBottomRightLon()));
        }
    }

    private void zoomToGaps() {
        Outcome o = selected();
        if (o == null || o.gaps.isEmpty() || MainApplication.getMap() == null) {
            return;
        }
        Bounds b = null;
        for (Gap g : o.gaps) {
            Bounds gb = Areas.bounds(g.track());
            if (b == null) {
                b = gb;
            } else {
                b.extend(gb);
            }
        }
        MainApplication.getMap().mapView.zoomTo(b);
    }

    private void openEditor() {
        Outcome o = selected();
        Relation r = o == null ? null : o.relation;
        if (r == null && o != null && !o.duplicates.isEmpty()) {
            r = o.duplicates.get(0);
        }
        if (r != null && r.getDataSet() == layer.getDataSet() && !r.isDeleted()) {
            RelationEditor.getEditor(layer, r, null).setVisible(true);
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {tr("Route"), tr("Result"), tr("Members"), tr("Split ways"), tr("Gaps"),
            tr("Matched"), tr("Warnings")};

        @Override
        public int getRowCount() {
            return outcomes.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c >= 2 && c <= 4 || c == 6 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int c) {
            Outcome o = outcomes.get(row);
            return switch (c) {
                case 0 -> o.name();
                case 1 -> o.status == Outcome.Status.CREATED ? o.summary()
                        : o.status == Outcome.Status.DUPLICATE_SKIPPED && !o.duplicates.isEmpty()
                        ? o.status.label() + " (" + o.duplicates.get(0).getDisplayName(DefaultNameFormatter.getInstance()) + ")"
                        : o.summary();
                case 2 -> o.relation == null ? null : o.relation.getMembersCount();
                case 3 -> o.relation == null ? null : o.splitWays;
                case 4 -> o.relation == null ? null : o.gaps.size();
                case 5 -> o.quality == null ? "" : String.format(Locale.ROOT, "%d%%", Math.round(o.quality[0] * 100));
                case 6 -> o.warnings.size();
                default -> null;
            };
        }
    }
}
