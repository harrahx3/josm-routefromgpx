// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * The tracks to process: a check box, the GPX file/track, the result of the last run, then one
 * column per tag key with the route's own values (blank: the common value applies).
 */
final class TrackTableModel extends AbstractTableModel {

    static final int COL_ENABLED = 0;
    static final int COL_SOURCE = 1;
    static final int COL_STATUS = 2;
    static final int FIXED = 3;

    private final List<TrackSource> tracks;
    private final List<String> keys;

    /** Both lists are kept (and changed) by the model, so they outlive the dialog. */
    TrackTableModel(List<TrackSource> tracks, List<String> keys) {
        this.tracks = tracks;
        this.keys = keys;
    }

    List<TrackSource> tracks() {
        return tracks;
    }

    List<String> keys() {
        return keys;
    }

    TrackSource get(int row) {
        return tracks.get(row);
    }

    void add(Collection<TrackSource> added) {
        if (added.isEmpty()) {
            return;
        }
        int first = tracks.size();
        for (TrackSource t : added) {
            t.tags.putIfAbsent("name", t.defaultName());
            tracks.add(t);
            t.tags.keySet().forEach(this::ensureKey);
        }
        fireTableStructureChanged();
        if (first < tracks.size()) {
            fireTableRowsInserted(first, tracks.size() - 1);
        }
    }

    void remove(int[] modelRows) {
        for (int i = modelRows.length - 1; i >= 0; i--) {
            tracks.remove(modelRows[i]);
        }
        fireTableDataChanged();
    }

    /** Adds a tag column if it is not there yet; returns its column index. */
    int ensureKey(String key) {
        int i = keys.indexOf(key);
        if (i < 0) {
            keys.add(key);
            fireTableStructureChanged();
            i = keys.size() - 1;
        }
        return FIXED + i;
    }

    /** Removes a tag column and the values in it. */
    void removeKey(String key) {
        if (keys.remove(key)) {
            tracks.forEach(t -> t.tags.remove(key));
            fireTableStructureChanged();
        }
    }

    void setTag(int row, String key, String value) {
        TrackSource t = tracks.get(row);
        if (value == null || value.isBlank()) {
            t.tags.remove(key);
        } else {
            t.tags.put(key, value.trim());
        }
        fireTableRowsUpdated(row, row);
    }

    String keyAt(int column) {
        return column >= FIXED ? keys.get(column - FIXED) : null;
    }

    List<TrackSource> enabled() {
        return tracks.stream().filter(t -> t.enabled).toList();
    }

    void setAllEnabled(boolean enabled) {
        tracks.forEach(t -> t.enabled = enabled);
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return tracks.size();
    }

    @Override
    public int getColumnCount() {
        return FIXED + keys.size();
    }

    @Override
    public String getColumnName(int column) {
        return switch (column) {
            case COL_ENABLED -> "";
            case COL_SOURCE -> tr("GPX track");
            case COL_STATUS -> tr("Last run");
            default -> keys.get(column - FIXED);
        };
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == COL_ENABLED ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == COL_ENABLED || column >= FIXED;
    }

    @Override
    public Object getValueAt(int row, int column) {
        TrackSource t = tracks.get(row);
        return switch (column) {
            case COL_ENABLED -> t.enabled;
            case COL_SOURCE -> t.label();
            case COL_STATUS -> t.status;
            default -> t.tags.getOrDefault(keys.get(column - FIXED), "");
        };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (column == COL_ENABLED) {
            tracks.get(row).enabled = Boolean.TRUE.equals(value);
            fireTableCellUpdated(row, column);
        } else if (column >= FIXED) {
            setTag(row, keys.get(column - FIXED), value == null ? "" : value.toString());
        }
    }

    /** For the CSV export: header (file, track, keys...) and one row per track. */
    List<List<String>> csvRows() {
        List<List<String>> rows = new ArrayList<>();
        for (TrackSource t : tracks) {
            List<String> row = new ArrayList<>();
            row.add(t.file);
            row.add(t.multi ? String.valueOf(t.number) : "");
            keys.forEach(k -> row.add(t.tags.getOrDefault(k, "")));
            rows.add(row);
        }
        return rows;
    }
}
