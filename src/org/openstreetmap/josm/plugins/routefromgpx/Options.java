// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.spi.preferences.Config;

/** Run options and the preferences that remember them. */
public record Options(OnDuplicate onDuplicate, boolean download, boolean openEditor, boolean showTracks,
                      boolean markGaps) {

    static final String PREFIX = "routefromgpx.";
    static final String PREF_COMMON_TAGS = PREFIX + "common_tags";
    static final String PREF_COLUMNS = PREFIX + "columns";
    static final String PREF_ON_DUPLICATE = PREFIX + "on_duplicate";
    static final String PREF_DOWNLOAD = PREFIX + "download";
    static final String PREF_OPEN_EDITOR = PREFIX + "open_editor";
    static final String PREF_SHOW_TRACKS = PREFIX + "show_tracks";
    static final String PREF_MARK_GAPS = PREFIX + "mark_gaps";
    static final String PREF_LAST_DIR = PREFIX + "last_dir";
    static final String PREF_RECURSIVE = PREFIX + "recursive";
    /** Long tracks are downloaded in boxes of at most this many square degrees, along the track. */
    static final String PREF_TILE_AREA = PREFIX + "download.tile_area";

    /** What to do when a route of the same kind with the same name, or most of the same ways, exists. */
    public enum OnDuplicate {
        SKIP, CREATE, ASK;

        @Override
        public String toString() {
            return switch (this) {
                case SKIP -> tr("skip the track");
                case CREATE -> tr("create it anyway");
                case ASK -> tr("ask");
            };
        }
    }

    static Options load() {
        OnDuplicate d;
        try {
            d = OnDuplicate.valueOf(Config.getPref().get(PREF_ON_DUPLICATE, OnDuplicate.ASK.name()));
        } catch (IllegalArgumentException e) {
            d = OnDuplicate.ASK;
        }
        return new Options(d, Config.getPref().getBoolean(PREF_DOWNLOAD, true),
                Config.getPref().getBoolean(PREF_OPEN_EDITOR, true), Config.getPref().getBoolean(PREF_SHOW_TRACKS, true),
                Config.getPref().getBoolean(PREF_MARK_GAPS, true));
    }

    void save() {
        Config.getPref().put(PREF_ON_DUPLICATE, onDuplicate.name());
        Config.getPref().putBoolean(PREF_DOWNLOAD, download);
        Config.getPref().putBoolean(PREF_OPEN_EDITOR, openEditor);
        Config.getPref().putBoolean(PREF_SHOW_TRACKS, showTracks);
        Config.getPref().putBoolean(PREF_MARK_GAPS, markGaps);
    }

    static Map<String, String> loadCommonTags() {
        // the default must be mutable: JOSM's ListSetting calls contains(null) on it
        List<String> saved = Config.getPref().getList(PREF_COMMON_TAGS, new ArrayList<>(List.of("type=route", "route=hiking")));
        return TagTemplate.parse(saved);
    }

    static void saveCommonTags(Map<String, String> tags) {
        Config.getPref().putList(PREF_COMMON_TAGS, TagTemplate.format(tags));
    }

    static List<String> loadColumns() {
        return Config.getPref().getList(PREF_COLUMNS, new ArrayList<>(List.of("name")));
    }

    static void saveColumns(List<String> columns) {
        Config.getPref().putList(PREF_COLUMNS, columns);
    }
}
