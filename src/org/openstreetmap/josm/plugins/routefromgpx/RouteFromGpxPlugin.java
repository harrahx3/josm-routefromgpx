// License: GPL v2 or later.
package org.openstreetmap.josm.plugins.routefromgpx;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/** Plugin entry point: adds Tools &gt; "Route relations from GPX...". */
public class RouteFromGpxPlugin extends Plugin {

    public RouteFromGpxPlugin(PluginInformation info) {
        super(info);
        MainMenu.add(MainApplication.getMenu().toolsMenu, new CreateRoutesAction());
    }
}
