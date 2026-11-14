package org.openstreetmap.josm.plugins.rivertracer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.NoteLayer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Logging;

/**
 * Utility class for capturing map imagery.
 */
public class ImageUtils {

    private ImageUtils() {
    }

    /**
     * Captures the current background imagery of the MapView as a BufferedImage,
     * excluding OSM vector data, GPX tracks, notes, and UI overlays so they do not
     * pollute pixel analysis.
     *
     * @param mv The MapView to capture.
     * @return A BufferedImage containing the imagery content, or null if no imagery is available or an error occurs.
     */
    public static BufferedImage getMapSnapshot(MapView mv) {
        try {
            int width = mv.getWidth();
            int height = mv.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }

            List<Layer> imageryLayers = getVisibleImageryLayers(mv);
            if (imageryLayers.isEmpty() || !mv.prepareToDraw()) {
                return null;
            }

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setClip(0, 0, width, height);
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, width, height);
                for (Layer layer : imageryLayers) {
                    mv.paintLayer(layer, g);
                }
            } finally {
                g.dispose();
            }
            return img;
        } catch (Exception ex) {
            Logging.error("Failed to capture map snapshot.", ex);
            return null;
        }
    }

    private static List<Layer> getVisibleImageryLayers(MapView mv) {
        List<Layer> result = new ArrayList<>();
        if (mv.getLayerManager() == null) {
            return result;
        }
        for (Layer layer : mv.getLayerManager().getVisibleLayersInZOrder()) {
            if (layer.isVisible() && isImageryOrBackgroundLayer(layer)) {
                result.add(layer);
            }
        }
        return result;
    }

    private static boolean isImageryOrBackgroundLayer(Layer layer) {
        if (layer instanceof OsmDataLayer || layer instanceof GpxLayer || layer instanceof NoteLayer) {
            return false;
        }
        return layer instanceof ImageryLayer || layer.isBackgroundLayer();
    }
}
