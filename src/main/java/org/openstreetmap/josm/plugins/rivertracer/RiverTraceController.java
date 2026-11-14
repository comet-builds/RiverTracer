package org.openstreetmap.josm.plugins.rivertracer;

import java.awt.Point;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.tools.Logging;

/**
 * Orchestrates the tracing process, handling background threads and UI updates.
 */
public class RiverTraceController {

    private static final int GHOST_DEBOUNCE_MS = 80;
    private static final long TILE_SETTLE_REFRESH_MS = 1500L;

    private final RiverTracerPreferences preferences;
    private final List<Point> ghostPath = Collections.synchronizedList(new ArrayList<>());
    private final Timer debounceTimer;
    private final AtomicLong ghostRequestId = new AtomicLong();
    private final ExecutorService traceExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable);
        t.setDaemon(true);
        t.setName("RiverTrace-Executor-" + t.getId());
        return t;
    });
    
    private BufferedImage cachedSnapshot;
    private EastNorth cachedCenter;
    private double cachedScale;
    private int cachedLayersHash;
    private long cachedTimestamp;
    private boolean tileRefreshDone;
    
    private Point lastMousePoint;
    private MapView lastMapView;

    public RiverTraceController() {
        this.preferences = new RiverTracerPreferences();
        this.debounceTimer = new Timer(GHOST_DEBOUNCE_MS, e -> performGhostTrace());
        this.debounceTimer.setRepeats(false);
    }

    /**
     * Updates the ghost trace preview based on mouse position.
     * Includes debouncing to prevent excessive CPU usage.
     */
    public void updateGhostTrace(Point p, MapView mv) {
        this.lastMousePoint = p;
        this.lastMapView = mv;
        this.debounceTimer.restart();
    }

    public void resetGhostTrace() {
        this.ghostRequestId.incrementAndGet();
        this.ghostPath.clear();
        this.lastMapView = null;
        this.lastMousePoint = null;
        if (this.debounceTimer.isRunning()) {
            this.debounceTimer.stop();
        }
    }

    public List<Point> getGhostPath() {
        synchronized(ghostPath) {
            return new ArrayList<>(ghostPath);
        }
    }

    private void performGhostTrace() {
        final Point capturedMousePoint = lastMousePoint;
        final MapView capturedMapView = lastMapView;

        if (capturedMousePoint == null || capturedMapView == null) return;
        final long requestId = ghostRequestId.incrementAndGet();

        executeTrace(capturedMousePoint, capturedMapView, requestId, (fullPath) -> {
            if (this.lastMapView != capturedMapView || ghostRequestId.get() != requestId) return;

            synchronized(ghostPath) {
                ghostPath.clear();
                ghostPath.addAll(fullPath);
            }
            capturedMapView.repaint();
        });
    }
    
    /**
     * Executes the final trace and adds the result to the JOSM dataset.
     */
    public void startTrace(Point start, MapView mv) {
        executeTrace(start, mv, 0L, (fullPath) -> {
            if (fullPath.size() < 2) return;
            new OsmWayBuilder().buildWay(fullPath, mv, preferences.getOptions());
        });
    }

    private void executeTrace(Point start, MapView mv, long expectedGhostId, Consumer<List<Point>> onSuccess) {
        updateCacheIfNeeded(mv);
        if (cachedSnapshot == null) return;

        final BufferedImage snapshot = cachedSnapshot;
        final RiverTracingOptions options = preferences.getOptions();
        final List<Line2D> waterways = getWaterwaySegments(mv);
        
        final EastNorth initialCenter = mv.getCenter();
        final double initialScale = mv.getScale();

        traceExecutor.submit(() -> {
            try {
                if (expectedGhostId != 0L && ghostRequestId.get() != expectedGhostId) {
                    return;
                }
                RiverTracingEngine engine = new RiverTracingEngine(options);
                List<Point> fullPath = engine.trace(snapshot, start, waterways);

                SwingUtilities.invokeLater(() -> {
                    if (!mv.getCenter().equals(initialCenter) || Math.abs(mv.getScale() - initialScale) > 1e-6) {
                        return;
                    }
                    onSuccess.accept(fullPath);
                });
            } catch (Exception e) {
                Logging.error("Error during river tracing.", e);
            }
        });
    }

    private void updateCacheIfNeeded(MapView mv) {
        EastNorth currentCenter = mv.getCenter();
        double currentScale = mv.getScale();
        int currentLayersHash = computeVisibleLayersHash(mv);
        long now = System.currentTimeMillis();

        boolean viewportChanged = cachedSnapshot == null
            || !currentCenter.equals(cachedCenter)
            || Math.abs(currentScale - cachedScale) > 1e-6
            || currentLayersHash != cachedLayersHash
            || cachedSnapshot.getWidth() != mv.getWidth()
            || cachedSnapshot.getHeight() != mv.getHeight();

        boolean settleRefreshDue = !tileRefreshDone && (now - cachedTimestamp >= TILE_SETTLE_REFRESH_MS);

        if (viewportChanged || settleRefreshDue) {
            cachedSnapshot = ImageUtils.getMapSnapshot(mv);
            cachedCenter = currentCenter;
            cachedScale = currentScale;
            cachedLayersHash = currentLayersHash;
            if (viewportChanged) {
                cachedTimestamp = now;
                tileRefreshDone = false;
            } else {
                tileRefreshDone = true;
            }
        }
    }

    private int computeVisibleLayersHash(MapView mv) {
        if (mv.getLayerManager() == null) return 0;
        int hash = 1;
        for (Layer layer : mv.getLayerManager().getVisibleLayersInZOrder()) {
            hash = 31 * hash + System.identityHashCode(layer);
            hash = 31 * hash + Double.hashCode(layer.getOpacity());
        }
        return hash;
    }

    private List<Line2D> getWaterwaySegments(MapView mv) {
        List<Line2D> segments = new ArrayList<>();
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) return segments;

        org.openstreetmap.josm.data.Bounds bounds = mv.getRealBounds();
        BBox viewBBox = new BBox(bounds.getMin().lon(), bounds.getMin().lat(), bounds.getMax().lon(), bounds.getMax().lat());

        Collection<Way> ways = ds.searchWays(viewBBox);

        for (Way w : ways) {
            if (w.isDeleted() || !w.hasKey("waterway")) continue;
            
            for (int i = 0; i < w.getNodesCount() - 1; i++) {
                Node n1 = w.getNode(i);
                Node n2 = w.getNode(i + 1);
                if (n1.isDeleted() || n2.isDeleted()) continue;

                Point p1 = mv.getPoint(n1.getCoor());
                Point p2 = mv.getPoint(n2.getCoor());

                segments.add(new Line2D.Float(p1, p2));
            }
        }
        return segments;
    }
}
