package org.openstreetmap.josm.plugins.rivertracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;

/**
 * Handles the construction of OSM Ways and Nodes from the traced pixel path.
 */
public class OsmWayBuilder {

    private static final double SNAP_DISTANCE_METERS = 15.0;
    private static final double SEARCH_PADDING_DEG = 0.001;
    private static final String TAG_WATERWAY = "waterway";

    /**
     * Converts a list of screen points into an OSM way, snapping to existing water networks if possible.
     *
     * @param path The list of screen points.
     * @param mv The current MapView.
     * @param options The user options for tagging.
     */
    public void buildWay(List<Point> path, MapView mv, RiverTracingOptions options) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) return;

        List<Node> nodes = new ArrayList<>();
        List<Command> cmds = new ArrayList<>();

        for (int i = 0; i < path.size(); i++) {
            Point p = path.get(i);
            LatLon ll = mv.getLatLon(p.x, p.y);

            if (i == 0 || i == path.size() - 1) {
                nodes.add(snapPoint(ds, ll, cmds));
            } else {
                nodes.add(createNewNode(ds, ll, cmds));
            }
        }

        Way w = new Way();
        w.setNodes(nodes);

        String waterwayType = options.isRiver() ? "river" : "stream";
        w.put(TAG_WATERWAY, waterwayType);
        if (options.isIntermittent()) {
            w.put("intermittent", "yes");
        }

        cmds.add(new AddCommand(ds, w));

        Command finalCommand = new SequenceCommand(tr("Trace River"), cmds);
        UndoRedoHandler.getInstance().add(finalCommand);

        mv.repaint();
    }

    private Node snapPoint(DataSet ds, LatLon ll, List<Command> cmds) {
        Node snapNode = findSnapNode(ds, ll);
        
        if (snapNode != null) {
            return snapNode;
        }

        Node segmentNode = snapToSegment(ds, ll, cmds);
        if (segmentNode != null) {
            return segmentNode;
        } else {
            return createNewNode(ds, ll, cmds);
        }
    }

    private Node createNewNode(DataSet ds, LatLon ll, List<Command> cmds) {
        Node n = new Node(ll);
        cmds.add(new AddCommand(ds, n));
        return n;
    }

    private Node findSnapNode(DataSet ds, LatLon ll) {
        BBox searchBox = new BBox(
            ll.getX() - SEARCH_PADDING_DEG, 
            ll.getY() - SEARCH_PADDING_DEG, 
            ll.getX() + SEARCH_PADDING_DEG, 
            ll.getY() + SEARCH_PADDING_DEG
        );
        Collection<Node> candidates = ds.searchNodes(searchBox);

        Node bestNode = null;
        double minDist = Double.MAX_VALUE;

        for (Node n : candidates) {
            if (n.isDeleted()
                    || n.getParentWays().stream()
                            .noneMatch(w -> !w.isDeleted() && w.hasKey(TAG_WATERWAY))) {
                continue;
            }

            double dist = n.getCoor().greatCircleDistance(ll);
            if (dist < SNAP_DISTANCE_METERS && dist < minDist) {
                minDist = dist;
                bestNode = n;
            }
        }
        return bestNode;
    }

    private Node snapToSegment(DataSet ds, LatLon ll, List<Command> cmds) {
        BBox searchBox = new BBox(
            ll.getX() - SEARCH_PADDING_DEG, 
            ll.getY() - SEARCH_PADDING_DEG, 
            ll.getX() + SEARCH_PADDING_DEG, 
            ll.getY() + SEARCH_PADDING_DEG
        );
        Collection<Way> ways = ds.searchWays(searchBox);

        WaySegment bestSeg = null;
        LatLon bestProj = null;
        double minDist = Double.MAX_VALUE;

        for (Way w : ways) {
            if (w.isDeleted() || !w.hasKey(TAG_WATERWAY)) continue;

            for (int i = 0; i < w.getNodesCount() - 1; i++) {
                Node n1 = w.getNode(i);
                Node n2 = w.getNode(i + 1);

                if (n1.isDeleted() || n2.isDeleted()) continue;

                LatLon proj = getClosestPointOnSegment(n1.getCoor(), n2.getCoor(), ll);
                double dist = proj.greatCircleDistance(ll);

                if (dist < SNAP_DISTANCE_METERS && dist < minDist) {
                    minDist = dist;
                    bestSeg = new WaySegment(w, i);
                    bestProj = proj;
                }
            }
        }

        if (bestSeg == null) return null;

        Node newNode = new Node(bestProj);
        cmds.add(new AddCommand(ds, newNode));

        Way w = bestSeg.getWay();
        Way newWay = new Way(w);
        newWay.addNode(bestSeg.getLowerIndex() + 1, newNode);

        cmds.add(new ChangeCommand(w, newWay));
        return newNode;
    }

    private LatLon getClosestPointOnSegment(LatLon p1, LatLon p2, LatLon p) {
        double x1 = p1.lon();
        double y1 = p1.lat();
        double x2 = p2.lon();
        double y2 = p2.lat();
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0 && dy == 0) return p1;

        double t = ((p.lon() - x1) * dx + (p.lat() - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));

        return new LatLon(y1 + t * dy, x1 + t * dx);
    }
}
