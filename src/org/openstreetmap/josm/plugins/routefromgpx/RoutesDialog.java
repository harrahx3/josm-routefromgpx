// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableColumn;

import org.openstreetmap.josm.actions.ExtensionFileFilter;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.tagging.TagEditorModel;
import org.openstreetmap.josm.gui.tagging.TagEditorPanel;
import org.openstreetmap.josm.gui.tagging.TagModel;
import org.openstreetmap.josm.gui.tagging.ac.AutoCompletionManager;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.util.WindowGeometry;
import org.openstreetmap.josm.gui.widgets.AbstractFileChooser;
import org.openstreetmap.josm.gui.widgets.FileChooserManager;
import org.openstreetmap.josm.plugins.routefromgpx.core.RouteProfile;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * The main dialog: GPX tracks in a table (one row per track, one column per tag specific to
 * a route), the tags common to all routes, a preview of the selected route's tags, and the run
 * options.
 */
final class RoutesDialog extends ExtendedDialog {

    private final TrackTableModel model;
    private final JTable table;
    private final TagEditorModel commonModel = new TagEditorModel();
    private final TagEditorPanel commonPanel;
    private final JTextPane preview = new JTextPane();
    private final JComboBox<Options.OnDuplicate> onDuplicate = new JComboBox<>(Options.OnDuplicate.values());
    private final JCheckBox download = new JCheckBox(tr("Download missing OSM data"));
    private final JCheckBox openEditor = new JCheckBox(tr("Open the relation editor (single route)"));
    private final JCheckBox showTracks = new JCheckBox(tr("Show the GPX tracks of the files in a layer"));
    private final JLabel count = new JLabel();

    RoutesDialog(TrackTableModel model) {
        super(MainApplication.getMainFrame(), tr("Route relations from GPX"), tr("Create relations"), tr("Cancel"));
        this.model = model;
        setButtonIcons("ok", "cancel");
        setCancelButton(2);
        setRememberWindowGeometry(Options.PREFIX + "dialog.geometry",
                WindowGeometry.centerInWindow(MainApplication.getMainFrame(), new Dimension(1000, 720)));

        commonModel.initFromTags(Options.loadCommonTags());
        commonModel.ensureOneTag();
        commonPanel = new TagEditorPanel(commonModel, null, 255);
        initAutoCompletion();

        table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setComponentPopupMenu(buildPopup());
        table.setDefaultEditor(String.class, new ValueCellEditor());
        model.addTableModelListener(e -> {
            GuiHelper.runInEDT(this::sizeColumns);
            updatePreview();
        });
        table.getSelectionModel().addListSelectionListener(e -> updatePreview());
        commonModel.addTableModelListener(e -> updatePreview());

        Options o = Options.load();
        onDuplicate.setSelectedItem(o.onDuplicate());
        download.setSelected(o.download());
        openEditor.setSelected(o.openEditor());
        showTracks.setSelected(o.showTracks());

        preview.setEditable(false);
        preview.setContentType("text/html");

        setContent(buildContent(), false);
        sizeColumns();
        updatePreview();
    }

    // ------------------------------------------------------------------ layout

    private JPanel buildContent() {
        JPanel common = new JPanel(new BorderLayout(0, 4));
        common.setBorder(BorderFactory.createTitledBorder(tr("Tags of every route")));
        common.add(commonPanel, BorderLayout.CENTER);
        JPanel commonSouth = new JPanel(new BorderLayout());
        JButton suggest = new JButton(tr("Suggested tags"));
        suggest.setToolTipText(tr("Add a common route tag with one of its usual values"));
        suggest.addActionListener(e -> {
            JPopupMenu menu = suggestionMenu();
            menu.show(suggest, 0, suggest.getHeight());
        });
        JPanel suggestBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        suggestBar.add(suggest);
        commonSouth.add(suggestBar, BorderLayout.WEST);
        commonSouth.add(help(tr("Placeholders, in any value: {0}. A value set in a route''s column replaces the common one.",
                "{file} {folder} {track} {desc} {n} {distance} {ascent} {descent}")), BorderLayout.CENTER);
        common.add(commonSouth, BorderLayout.SOUTH);

        JPanel tracks = new JPanel(new BorderLayout(0, 4));
        tracks.setBorder(BorderFactory.createTitledBorder(tr("Routes (one per GPX track)")));
        tracks.add(buildToolbar(), BorderLayout.NORTH);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(BorderFactory.createTitledBorder(tr("Tags of the selected route")));
        JSplitPane tableAndPreview = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), previewScroll);
        tableAndPreview.setResizeWeight(0.75);
        tracks.add(tableAndPreview, BorderLayout.CENTER);
        tracks.add(help(tr("Empty cell: the common value applies. Select several rows and use \"Set tag...\" "
                + "(or right-click) to give them the same value.")), BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, common, tracks);
        split.setResizeWeight(0.3);

        JPanel options = new JPanel(new GridBagLayout());
        options.setBorder(BorderFactory.createTitledBorder(tr("Options")));
        JPanel dup = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        dup.add(new JLabel(tr("If a similar route exists (same name, or 80% of the same ways):") + " "));
        dup.add(onDuplicate);
        options.add(dup, GBC.eol().anchor(GBC.WEST));
        options.add(download, GBC.eol().anchor(GBC.WEST));
        options.add(openEditor, GBC.eol().anchor(GBC.WEST));
        options.add(showTracks, GBC.eol().anchor(GBC.WEST));
        options.add(new JPanel(), GBC.eol().fill(GBC.HORIZONTAL));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(options, BorderLayout.CENTER);
        bottom.add(count, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(split, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(980, 640));
        return content;
    }

    private static JLabel help(String text) {
        JLabel l = new JLabel("<html><i>" + Utils.escapeReservedCharactersHTML(text) + "</i></html>");
        l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return l;
    }

    private JPanel buildToolbar() {
        JPanel files = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        files.add(new JLabel(tr("Tracks:")));
        files.add(button(tr("Add files..."), this::addFiles));
        files.add(button(tr("Add folder..."), this::addFolder));
        files.add(button(tr("Add GPX layers"), this::addLayers));
        files.add(button(tr("Remove"), this::removeSelected));
        JPanel tags = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        tags.add(new JLabel(tr("Tags:")));
        tags.add(button(tr("Set tag..."), this::setTagOnSelected));
        tags.add(button(tr("Add column..."), this::addColumn));
        tags.add(button(tr("Remove column..."), this::removeColumn));
        tags.add(button(tr("Import CSV..."), this::importCsv));
        tags.add(button(tr("Export CSV..."), this::exportCsv));
        JPanel bar = new JPanel(new GridLayout(2, 1));
        bar.add(files);
        bar.add(tags);
        return bar;
    }

    private static JButton button(String label, Runnable r) {
        return new JButton(new AbstractAction(label) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(action(tr("Set tag on selected rows..."), this::setTagOnSelected));
        menu.add(action(tr("Check selected rows"), () -> setEnabledOnSelected(true)));
        menu.add(action(tr("Uncheck selected rows"), () -> setEnabledOnSelected(false)));
        menu.add(action(tr("Check all"), () -> model.setAllEnabled(true)));
        menu.add(action(tr("Uncheck all"), () -> model.setAllEnabled(false)));
        menu.addSeparator();
        menu.add(action(tr("Remove selected rows"), this::removeSelected));
        return menu;
    }

    private static AbstractAction action(String label, Runnable r) {
        return new AbstractAction(label) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private void sizeColumns() {
        if (table.getColumnCount() <= TrackTableModel.FIXED) {
            return;
        }
        int[] widths = {24, 200, 150};
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn c = table.getColumnModel().getColumn(i);
            int mi = table.convertColumnIndexToModel(i);
            if (mi < TrackTableModel.FIXED) {
                c.setPreferredWidth(widths[mi]);
                if (mi == TrackTableModel.COL_ENABLED) {
                    c.setMaxWidth(30);
                }
            } else {
                c.setPreferredWidth("name".equals(model.keyAt(mi)) ? 220 : 110);
            }
        }
    }

    // ------------------------------------------------------------------ rows

    private int[] selectedModelRows() {
        int[] view = table.getSelectedRows();
        int[] rows = new int[view.length];
        for (int i = 0; i < view.length; i++) {
            rows[i] = table.convertRowIndexToModel(view[i]);
        }
        Arrays.sort(rows);
        return rows;
    }

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        commonPanel.saveEdits();
    }

    private void addFiles() {
        AbstractFileChooser fc = new FileChooserManager(true, Options.PREF_LAST_DIR)
                .createFileChooser(true, tr("Add GPX files"), gpxFilter(), JFileChooser.FILES_ONLY)
                .openFileChooser(this);
        if (fc != null) {
            load(List.of(fc.getSelectedFiles()), null);
        }
    }

    private void addFolder() {
        AbstractFileChooser fc = new FileChooserManager(true, Options.PREF_LAST_DIR)
                .createFileChooser(false, tr("Add a folder of GPX files"), null, JFileChooser.DIRECTORIES_ONLY)
                .openFileChooser(this);
        if (fc == null || fc.getSelectedFile() == null) {
            return;
        }
        File dir = fc.getSelectedFile();
        boolean recursive = Config.getPref().getBoolean(Options.PREF_RECURSIVE, false);
        JCheckBox sub = new JCheckBox(tr("Include sub-folders"), recursive);
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null && subDirs.length > 0) {
            if (JOptionPane.showConfirmDialog(this, sub, tr("Add folder"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            Config.getPref().putBoolean(Options.PREF_RECURSIVE, sub.isSelected());
        }
        List<File> files = GpxTracks.gpxFiles(dir, subDirs != null && subDirs.length > 0 && sub.isSelected());
        if (files.isEmpty()) {
            JOptionPane.showMessageDialog(this, tr("No .gpx file in {0}", dir), tr("Add folder"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        File csv = new File(dir, "tags.csv");
        load(files, csv.isFile() ? csv : null);
    }

    private static ExtensionFileFilter gpxFilter() {
        return new ExtensionFileFilter("gpx", "gpx", tr("GPX files") + " (*.gpx)");
    }

    /** Reads the files in the background, then adds their tracks (and applies {@code csv} if not null). */
    private void load(List<File> files, File csv) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            List<TrackSource> loaded = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (File f : files) {
                try {
                    List<TrackSource> t = GpxTracks.read(f);
                    if (t.isEmpty()) {
                        errors.add(tr("{0}: no track with at least 2 points", f.getName()));
                    }
                    loaded.addAll(t);
                } catch (IOException | RuntimeException e) {
                    Logging.warn(e);
                    errors.add(e.getMessage());
                }
            }
            GuiHelper.runInEDT(() -> {
                setCursor(null);
                model.add(loaded);
                if (csv != null) {
                    applyCsv(csv, loaded);
                }
                if (!errors.isEmpty()) {
                    JOptionPane.showMessageDialog(this, String.join("\n", errors.subList(0, Math.min(20, errors.size())))
                            + (errors.size() > 20 ? "\n..." : ""), tr("Some files could not be read"), JOptionPane.WARNING_MESSAGE);
                }
            });
        }, "routefromgpx-load").start();
    }

    private void addLayers() {
        List<TrackSource> added = new ArrayList<>();
        for (GpxLayer layer : MainApplication.getLayerManager().getLayersOfType(GpxLayer.class)) {
            String name = GpxTracks.stem(layer.getName());
            File f = layer.data.storageFile;
            String folder = f != null && f.getAbsoluteFile().getParentFile() != null ? f.getAbsoluteFile().getParentFile().getName() : "";
            boolean present = model.tracks().stream().anyMatch(t -> t.file.equals(name) && !t.fromFile);
            if (!present) {
                added.addAll(GpxTracks.tracks(layer.data, name, folder, false));
            }
        }
        if (added.isEmpty()) {
            JOptionPane.showMessageDialog(this, tr("No GPX layer with tracks to add."), tr("Add GPX layers"),
                    JOptionPane.INFORMATION_MESSAGE);
        }
        model.add(added);
    }

    private void removeSelected() {
        stopEditing();
        model.remove(selectedModelRows());
    }

    private void setEnabledOnSelected(boolean enabled) {
        for (int r : selectedModelRows()) {
            model.get(r).enabled = enabled;
        }
        model.fireTableDataChanged();
    }

    // ------------------------------------------------------------------ columns and tags

    /**
     * Auto-completion of the common tag editor: the data layer's keys and values (or JOSM's presets
     * only, when there is no data layer), plus the usual route tags.
     */
    private void initAutoCompletion() {
        RouteTags.SUGGESTED.forEach((k, values) -> values.stream()
                .filter(v -> !v.contains("{"))
                .forEach(v -> AutoCompletionManager.rememberUserInput(k, v, false)));
        OsmDataLayer edit = MainApplication.getLayerManager().getEditLayer();
        try {
            commonPanel.initAutoCompletion(edit != null ? edit : new OsmDataLayer(new DataSet(), "routefromgpx", null));
        } catch (RuntimeException e) {
            Logging.warn(e);
        }
    }

    /** One entry per usual route key; keys with usual values get a sub-menu of them. */
    private JPopupMenu suggestionMenu() {
        commonPanel.saveEdits();
        JPopupMenu menu = new JPopupMenu();
        menu.add(action(tr("Length and climb of each track (distance, ascent, descent)"), () -> {
            setCommonTag("distance", "{distance}");
            setCommonTag("ascent", "{ascent}");
            setCommonTag("descent", "{descent}");
        }));
        menu.addSeparator();
        Map<String, String> current = commonModel.getTags();
        RouteTags.SUGGESTED.forEach((key, values) -> {
            String label = current.containsKey(key) ? key + " = " + current.get(key) : key;
            if (values.isEmpty()) {
                menu.add(action(label, () -> setCommonTag(key, current.getOrDefault(key, ""))));
            } else {
                JMenu sub = new JMenu(label);
                for (String v : values) {
                    sub.add(action(v, () -> setCommonTag(key, v)));
                }
                menu.add(sub);
            }
        });
        return menu;
    }

    /** Sets (or adds) a tag in the common tag editor. */
    private void setCommonTag(String key, String value) {
        TagModel existing = commonModel.get(key);
        if (existing != null) {
            commonModel.updateTagValue(existing, value);
        } else {
            // reuse the blank row the editor keeps at the end, if any
            TagModel blank = commonModel.get("");
            if (blank != null) {
                commonModel.updateTagName(blank, key);
                commonModel.updateTagValue(blank, value);
            } else {
                commonModel.add(key, value);
            }
        }
        commonModel.fireTableDataChanged();
        updatePreview();
    }

    /** Values offered for {@code key}: the usual ones, then those already used in its column. */
    private List<String> valueSuggestions(String key) {
        Set<String> values = new LinkedHashSet<>(RouteTags.values(key));
        String common = commonModel.getTags().get(key);
        if (common != null && !common.isBlank()) {
            values.add(common);
        }
        for (TrackSource t : model.tracks()) {
            String v = t.tags.get(key);
            if (v != null && !v.isBlank() && values.size() < 200) {
                values.add(v);
            }
        }
        return new ArrayList<>(values);
    }

    private static void fill(JComboBox<String> combo, List<String> values, String selected) {
        combo.removeAllItems();
        values.forEach(combo::addItem);
        combo.setSelectedItem(selected);
    }

    /** Editor of the tag cells: an editable combo box offering the usual values of the column's key. */
    private final class ValueCellEditor extends DefaultCellEditor {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("unchecked")
        ValueCellEditor() {
            super(new JComboBox<String>());
            JComboBox<String> combo = (JComboBox<String>) getComponent();
            combo.setEditable(true);
            setClickCountToStart(2);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getTableCellEditorComponent(JTable t, Object value, boolean isSelected, int row, int column) {
            JComboBox<String> combo = (JComboBox<String>) getComponent();
            String key = model.keyAt(t.convertColumnIndexToModel(column));
            List<String> values = valueSuggestions(key);
            values.add(0, "");
            fill(combo, values, value == null ? "" : value.toString());
            return combo;
        }
    }

    private JComboBox<String> keyCombo(Iterable<String> first) {
        Set<String> keys = new LinkedHashSet<>();
        first.forEach(keys::add);
        keys.addAll(RouteTags.SUGGESTED.keySet());
        JComboBox<String> combo = new JComboBox<>(keys.toArray(new String[0]));
        combo.setEditable(true);
        return combo;
    }

    private void setTagOnSelected() {
        stopEditing();
        int[] rows = selectedModelRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, tr("Select the rows first."), tr("Set tag"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<String> key = keyCombo(model.keys());
        int col = table.getSelectedColumn() >= 0 ? table.convertColumnIndexToModel(table.getSelectedColumn()) : -1;
        if (model.keyAt(col) != null) {
            key.setSelectedItem(model.keyAt(col));
        }
        JComboBox<String> value = new JComboBox<>();
        value.setEditable(true);
        value.setPrototypeDisplayValue("x".repeat(30));
        Runnable refill = () -> fill(value, valueSuggestions(String.valueOf(key.getSelectedItem()).trim()), "");
        refill.run();
        key.addActionListener(e -> refill.run());
        JPanel p = new JPanel(new GridBagLayout());
        p.add(new JLabel(tr("Key:")), GBC.std().insets(0, 0, 5, 5));
        p.add(key, GBC.eol().fill(GBC.HORIZONTAL));
        p.add(new JLabel(tr("Value:")), GBC.std().insets(0, 0, 5, 5));
        p.add(value, GBC.eol().fill(GBC.HORIZONTAL));
        p.add(new JLabel(tr("(empty value: use the common value again)")), GBC.eol());
        if (JOptionPane.showConfirmDialog(this, p, trn("Set tag on {0} route", "Set tag on {0} routes", rows.length, rows.length),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String k = String.valueOf(key.getSelectedItem()).trim();
        if (k.isEmpty()) {
            return;
        }
        model.ensureKey(k);
        for (int r : rows) {
            model.setTag(r, k, String.valueOf(value.getEditor().getItem()));
        }
    }

    private void addColumn() {
        stopEditing();
        JComboBox<String> key = keyCombo(List.of());
        if (JOptionPane.showConfirmDialog(this, key, tr("Tag key of the new column"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String k = String.valueOf(key.getSelectedItem()).trim();
            if (!k.isEmpty()) {
                model.ensureKey(k);
            }
        }
    }

    private void removeColumn() {
        stopEditing();
        if (model.keys().isEmpty()) {
            return;
        }
        JComboBox<String> key = new JComboBox<>(model.keys().toArray(new String[0]));
        int col = table.getSelectedColumn() >= 0 ? table.convertColumnIndexToModel(table.getSelectedColumn()) : -1;
        if (model.keyAt(col) != null) {
            key.setSelectedItem(model.keyAt(col));
        }
        if (JOptionPane.showConfirmDialog(this, key, tr("Remove the column and its values"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
            model.removeKey(String.valueOf(key.getSelectedItem()));
        }
    }

    // ------------------------------------------------------------------ CSV

    private void importCsv() {
        stopEditing();
        if (model.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, tr("Add the GPX files first; the CSV file gives the tags of each file."),
                    tr("Import CSV"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        AbstractFileChooser fc = new FileChooserManager(true, Options.PREF_LAST_DIR)
                .createFileChooser(false, tr("Import tags from CSV"), new ExtensionFileFilter("csv,txt", "csv",
                        tr("CSV files") + " (*.csv)"), JFileChooser.FILES_ONLY)
                .openFileChooser(this);
        if (fc != null && fc.getSelectedFile() != null) {
            applyCsv(fc.getSelectedFile(), model.tracks());
        }
    }

    private void applyCsv(File csv, List<TrackSource> tracks) {
        try (Reader in = Files.newBufferedReader(csv.toPath(), StandardCharsets.UTF_8)) {
            CsvTags.Table t = CsvTags.read(in);
            t.keys().forEach(model::ensureKey);
            List<Map<String, String>> unmatched = CsvTags.apply(t, tracks);
            model.fireTableDataChanged();
            String msg = tr("{0}: {1} row(s), {2} column(s) of tags.", csv.getName(), t.rows().size(), t.keys().size());
            if (!unmatched.isEmpty()) {
                List<String> files = unmatched.stream().map(r -> r.getOrDefault(CsvTags.FILE, "")).limit(15).toList();
                msg += "\n" + tr("{0} row(s) match no GPX track: {1}", unmatched.size(), String.join(", ", files));
            }
            JOptionPane.showMessageDialog(this, msg, tr("Import CSV"),
                    unmatched.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, tr("Could not read {0}:\n{1}", csv.getName(), e.getMessage()),
                    tr("Import CSV"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportCsv() {
        stopEditing();
        AbstractFileChooser fc = new FileChooserManager(false, Options.PREF_LAST_DIR)
                .createFileChooser(false, tr("Export the route tags to CSV"), new ExtensionFileFilter("csv", "csv",
                        tr("CSV files") + " (*.csv)"), JFileChooser.FILES_ONLY)
                .openFileChooser(this);
        if (fc == null || fc.getSelectedFile() == null) {
            return;
        }
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            f = new File(f.getPath() + ".csv");
        }
        List<String> header = new ArrayList<>(List.of(CsvTags.FILE, CsvTags.TRACK));
        header.addAll(model.keys());
        try (Writer out = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            CsvTags.write(out, header, model.csvRows());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, tr("Could not write {0}:\n{1}", f.getName(), e.getMessage()),
                    tr("Export CSV"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ preview and run

    Map<String, String> commonTags() {
        return commonModel.getTags();
    }

    static Map<String, String> finalTags(Map<String, String> common, TrackSource t) {
        return TagTemplate.merge(common, t.tags, t.variables());
    }

    private void updatePreview() {
        int n = model.enabled().size();
        count.setText(" " + trn("{0} route will be created", "{0} routes will be created", n, n));
        int view = table.getSelectedRow();
        if (view < 0 || view >= table.getRowCount()) {
            preview.setText("<html><i>" + Utils.escapeReservedCharactersHTML(tr("Select a route to see its tags."))
                    + "</i></html>");
            return;
        }
        TrackSource t = model.get(table.convertRowIndexToModel(view));
        Map<String, String> tags = finalTags(commonModel.getTags(), t);
        StringBuilder html = new StringBuilder("<html><body style='font-family:monospace'>");
        tags.forEach((k, v) -> html.append("<b>").append(Utils.escapeReservedCharactersHTML(k)).append("</b>=")
                .append(Utils.escapeReservedCharactersHTML(v)).append(t.tags.containsKey(k) ? "" : " <font color=gray>*</font>")
                .append("<br>"));
        for (String p : TagTemplate.problems(tags)) {
            html.append("<font color=red>").append(Utils.escapeReservedCharactersHTML(p)).append("</font><br>");
        }
        html.append("<br><font color=gray>* ").append(Utils.escapeReservedCharactersHTML(tr("common value"))).append("</font>");
        html.append("<br><font color=gray>").append(Utils.escapeReservedCharactersHTML(
                tr("{0} track points", t.points.size()))).append("</font></body></html>");
        preview.setText(html.toString());
        preview.setCaretPosition(0);
    }

    Options options() {
        return new Options((Options.OnDuplicate) onDuplicate.getSelectedItem(), download.isSelected(),
                openEditor.isSelected(), showTracks.isSelected());
    }

    @Override
    protected void buttonAction(int buttonIndex, ActionEvent evt) {
        if (buttonIndex == 0) {
            stopEditing();
            if (!validateInput()) {
                return;
            }
            Options.saveCommonTags(commonModel.getTags());
            Options.saveColumns(model.keys());
            options().save();
        }
        super.buttonAction(buttonIndex, evt);
    }

    private boolean validateInput() {
        List<TrackSource> enabled = model.enabled();
        if (enabled.isEmpty()) {
            JOptionPane.showMessageDialog(this, tr("No route to create: add GPX tracks and check them."),
                    tr("Route relations from GPX"), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        Map<String, String> common = commonModel.getTags();
        List<String> problems = new ArrayList<>();
        for (TrackSource t : enabled) {
            for (String p : TagTemplate.problems(finalTags(common, t))) {
                problems.add(t.label() + ": " + p);
            }
        }
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, tr("Fix these tags first:") + "\n"
                    + String.join("\n", problems.subList(0, Math.min(20, problems.size())))
                    + (problems.size() > 20 ? "\n..." : "") + "\n\n"
                    + tr("Known route types: {0}", String.join(", ", RouteProfile.KNOWN)),
                    tr("Route relations from GPX"), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    /** Lets the caller select the first row (for a dialog opened with tracks already in it). */
    void selectFirst() {
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }
}
