package org.openstreetmap.josm.plugins.rivertracer;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class RiverTracerPlugin extends Plugin {

    public RiverTracerPlugin(PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            newFrame.addMapMode(new IconToggleButton(new RiverTraceMode(newFrame)));
            newFrame.addToggleDialog(new RiverTracerSidebar());
        }
    }
}
