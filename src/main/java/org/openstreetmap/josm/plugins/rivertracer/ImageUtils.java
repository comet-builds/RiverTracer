package org.openstreetmap.josm.plugins.rivertracer;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.tools.Logging;

/**
 * Utility class for capturing map imagery.
 */
public class ImageUtils {

    /**
     * Captures the current content of the MapView as a BufferedImage.
     * @param mv The MapView to capture.
     * @return A BufferedImage containing the map content, or null if an error occurs.
     */
    public static BufferedImage getMapSnapshot(MapView mv) {
        try {
            BufferedImage img = new BufferedImage(mv.getWidth(), mv.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setClip(0, 0, mv.getWidth(), mv.getHeight());
            mv.print(g);
            g.dispose();
            return img;
        } catch (Exception ex) {
            Logging.error(ex);
            return null;
        }
    }
}
