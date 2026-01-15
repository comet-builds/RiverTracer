package org.openstreetmap.josm.plugins.rivertracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * MapMode that allows users to click on imagery to trace rivers.
 * Displays a ghost trace preview on hover.
 */
public class RiverTraceMode extends MapMode implements MouseMotionListener, MapViewPaintable, NavigatableComponent.ZoomChangeListener {

    private final RiverTraceController controller;

    public RiverTraceMode(MapFrame mapFrame) {
        super(
            tr("RiverTracer"),
            "river_icon", 
            tr("Trace rivers automatically from imagery"),
            Shortcut.registerShortcut("mapmode:rivertracer", tr("Mode: {0}", tr("RiverTracer")), KeyEvent.VK_G, Shortcut.CTRL),
            Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        );
        this.controller = new RiverTraceController();
    }

    @Override
    public void enterMode() {
        super.enterMode();
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MapView mv = MainApplication.getMap().mapView;
            mv.addMouseListener(this);
            mv.addMouseMotionListener(this);
            mv.addTemporaryLayer(this);
            mv.addZoomChangeListener(this);
            mv.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }
    }

    @Override
    public void exitMode() {
        super.exitMode();
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MapView mv = MainApplication.getMap().mapView;
            mv.removeMouseListener(this);
            mv.removeMouseMotionListener(this);
            mv.removeTemporaryLayer(this);
            mv.removeZoomChangeListener(this);
            mv.setCursor(Cursor.getDefaultCursor());
            controller.resetGhostTrace();
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) { }

    @Override
    public void mousePressed(MouseEvent e) {
        controller.resetGhostTrace();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            controller.updateGhostTrace(e.getPoint(), MainApplication.getMap().mapView);
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        controller.resetGhostTrace();
    }

    @Override
    public void zoomChanged() {
        controller.resetGhostTrace();
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        List<Point> ghostPath = controller.getGhostPath();
        if (ghostPath != null && ghostPath.size() > 1) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setTransform(new AffineTransform());
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2));

                GeneralPath path = new GeneralPath();
                path.moveTo(ghostPath.get(0).x, ghostPath.get(0).y);
                for (int i = 1; i < ghostPath.size(); i++) {
                    path.lineTo(ghostPath.get(i).x, ghostPath.get(i).y);
                }
                g2.draw(path);
            } finally {
                g2.dispose();
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e)) return;

        try {
            if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) return;

            if (MainApplication.getLayerManager().getEditDataSet() == null) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Please create a Data Layer first.",
                    "No Data Layer",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            controller.startTrace(e.getPoint(), MainApplication.getMap().mapView);
        } catch (Exception ex) {
            Logging.error(ex);
        }
    }

    @Override
    public boolean layerIsSupported(org.openstreetmap.josm.gui.layer.Layer l) { return true; }
}
