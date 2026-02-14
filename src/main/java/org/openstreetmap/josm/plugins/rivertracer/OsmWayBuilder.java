package org.openstreetmap.josm.plugins.rivertracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.conflict.tags.CombinePrimitiveResolverDialog;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.UserCancelException;

/**
 * Handles the construction of OSM Ways and Nodes from the traced pixel path.
 */
public class OsmWayBuilder {

    private static final double SNAP_DISTANCE_METERS = 15.0;
    private static final double SEARCH_PADDING_DEG = 0.001;
    private static final String TAG_WATERWAY = "waterway";

    private static class ConnectionInfo {
        Node node;
        Way way;
        boolean isStart;
        boolean isEnd;

        boolean isValid() {
            return node != null && way != null;
        }
    }

    /**
     * Converts a list of screen points into an OSM way, snapping to existing water networks if possible.
     *
     * @param path The list of screen points.
     * @param mv The current MapView.
     * @param options The user options for tagging.
     */
    public void buildWay(List<Point> path, MapView mv, RiverTracingOptions options) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null || path.size() < 2) return;

        LatLon startLL = mv.getLatLon(path.get(0).x, path.get(0).y);
        LatLon endLL = mv.getLatLon(path.get(path.size() - 1).x, path.get(path.size() - 1).y);

        ConnectionInfo startConn = analyzeConnection(ds, startLL);
        ConnectionInfo endConn = analyzeConnection(ds, endLL);

        List<Command> cmds = new ArrayList<>();

        try {
            if (canMergeBoth(startConn, endConn)) {
                mergeBoth(ds, startConn, endConn, path, mv, cmds);
            } else if (canMerge(startConn)) {
                mergeOne(ds, startConn, path, mv, cmds, true);
            } else if (canMerge(endConn)) {
                mergeOne(ds, endConn, path, mv, cmds, false);
            } else {
                createFreshWay(ds, path, mv, cmds, options, startConn, endConn);
            }

            if (!cmds.isEmpty()) {
                Command finalCommand = new SequenceCommand(tr("Trace River"), cmds);
                UndoRedoHandler.getInstance().add(finalCommand);
                mv.repaint();
            }
        } catch (UserCancelException e) {
            // User cancelled the conflict dialog, abort operation
        } catch (Exception e) {
            Logging.error(e);
        }
    }

    private ConnectionInfo analyzeConnection(DataSet ds, LatLon ll) {
        ConnectionInfo info = new ConnectionInfo();
        Node snapNode = findSnapNode(ds, ll);

        if (snapNode != null) {
            info.node = snapNode;
            // Check if this node is an endpoint of a waterway
            for (Way w : snapNode.getParentWays()) {
                if (!w.isDeleted() && w.hasKey(TAG_WATERWAY)) {
                    boolean found = false;
                    if (w.getNode(0) == snapNode) {
                        info.way = w;
                        info.isStart = true;
                        found = true;
                    } else if (w.getNode(w.getNodesCount() - 1) == snapNode) {
                        info.way = w;
                        info.isEnd = true;
                        found = true;
                    }

                    if (found) {
                        break; // Prefer start/end
                    }
                }
            }
        }
        return info;
    }

    private boolean canMerge(ConnectionInfo info) {
        return info.isValid() && (info.isStart || info.isEnd);
    }

    private boolean canMergeBoth(ConnectionInfo start, ConnectionInfo end) {
        if (!canMerge(start) || !canMerge(end)) return false;
        if (start.way == end.way) return false; // Don't merge a way to itself (loop)

        // Check for direction mismatch
        return (start.isEnd && end.isStart) || (start.isStart && end.isEnd);
    }

    private void mergeOne(DataSet ds, ConnectionInfo conn, List<Point> path, MapView mv, List<Command> cmds, boolean isStartConn) {
        Way targetWay = conn.way;
        Node otherEndSnap = null;

        if (isStartConn) {
             LatLon ll = mv.getLatLon(path.get(path.size()-1).x, path.get(path.size()-1).y);
             otherEndSnap = snapPoint(ds, ll, cmds);
        } else {
             LatLon ll = mv.getLatLon(path.get(0).x, path.get(0).y);
             otherEndSnap = snapPoint(ds, ll, cmds);
        }

        List<Node> segmentNodes = generateSegmentNodes(ds, path, mv, cmds,
            isStartConn ? conn.node : otherEndSnap,
            isStartConn ? otherEndSnap : conn.node
        );

        List<Node> finalNodes = new ArrayList<>(targetWay.getNodes());

        if (isStartConn) {
            if (conn.isEnd) {
                List<Node> toAppend = segmentNodes.subList(1, segmentNodes.size());
                finalNodes.addAll(toAppend);
            } else if (conn.isStart) {
                List<Node> toPrepend = new ArrayList<>(segmentNodes.subList(1, segmentNodes.size()));
                Collections.reverse(toPrepend);
                finalNodes.addAll(0, toPrepend);
            }
        } else {
            if (conn.isStart) {
                List<Node> toPrepend = segmentNodes.subList(0, segmentNodes.size() - 1);
                finalNodes.addAll(0, toPrepend);
            } else if (conn.isEnd) {
                List<Node> toAppend = new ArrayList<>(segmentNodes.subList(0, segmentNodes.size() - 1));
                Collections.reverse(toAppend);
                finalNodes.addAll(toAppend);
            }
        }

        Way newWay = new Way(targetWay);
        newWay.setNodes(finalNodes);
        cmds.add(new ChangeCommand(targetWay, newWay));
    }

    private void mergeBoth(DataSet ds, ConnectionInfo start, ConnectionInfo end, List<Point> path, MapView mv, List<Command> cmds) throws UserCancelException {
        // Generate nodes for the bridge.
        List<Node> bridgeNodes = generateSegmentNodes(ds, path, mv, cmds, start.node, end.node);

        Way firstWay;
        Way secondWay;
        List<Node> midNodes;

        if (start.isEnd && end.isStart) {
            firstWay = start.way;
            secondWay = end.way;
            midNodes = bridgeNodes.subList(1, bridgeNodes.size() - 1);

            List<Node> finalNodes = new ArrayList<>(firstWay.getNodes());
            finalNodes.addAll(midNodes);
            finalNodes.addAll(secondWay.getNodes());

            applyMerge(firstWay, secondWay, finalNodes, cmds);

        } else {
            firstWay = end.way;
            secondWay = start.way;

            List<Node> reversedBridge = new ArrayList<>(bridgeNodes);
            Collections.reverse(reversedBridge);
            midNodes = reversedBridge.subList(1, reversedBridge.size() - 1);

            List<Node> finalNodes = new ArrayList<>(firstWay.getNodes());
            finalNodes.addAll(midNodes);
            finalNodes.addAll(secondWay.getNodes());

            applyMerge(firstWay, secondWay, finalNodes, cmds);
        }
    }

    private void applyMerge(Way keepWay, Way deleteWay, List<Node> newNodes, List<Command> cmds) throws UserCancelException {
        // Handle Tag Conflicts
        List<Way> waysToMerge = new ArrayList<>();
        waysToMerge.add(keepWay);
        waysToMerge.add(deleteWay);

        TagCollection wayTags = TagCollection.unionOfAllPrimitives(waysToMerge);

        List<Command> resolution = CombinePrimitiveResolverDialog.launchIfNecessary(
            wayTags,
            waysToMerge,
            Collections.singleton(keepWay)
        );

        cmds.addAll(resolution);

        Way newWay = new Way(keepWay);
        newWay.setNodes(newNodes);

        cmds.add(new ChangeCommand(keepWay, newWay));
        cmds.add(new DeleteCommand(deleteWay));
    }

    private void createFreshWay(DataSet ds, List<Point> path, MapView mv, List<Command> cmds, RiverTracingOptions options, ConnectionInfo startConn, ConnectionInfo endConn) {
        Node startNode = (startConn.isValid()) ? startConn.node : snapPoint(ds, mv.getLatLon(path.get(0).x, path.get(0).y), cmds);
        Node endNode = (endConn.isValid()) ? endConn.node : snapPoint(ds, mv.getLatLon(path.get(path.size()-1).x, path.get(path.size()-1).y), cmds);

        List<Node> nodes = generateSegmentNodes(ds, path, mv, cmds, startNode, endNode);

        Way w = new Way();
        w.setNodes(nodes);

        String waterwayType = options.isRiver() ? "river" : "stream";
        w.put(TAG_WATERWAY, waterwayType);
        if (options.isIntermittent()) {
            w.put("intermittent", "yes");
        }

        cmds.add(new AddCommand(ds, w));
    }

    // --- Helper Methods ---

    private List<Node> generateSegmentNodes(DataSet ds, List<Point> path, MapView mv, List<Command> cmds, Node startNode, Node endNode) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(startNode);

        for (int i = 1; i < path.size() - 1; i++) {
            Point p = path.get(i);
            LatLon ll = mv.getLatLon(p.x, p.y);
            nodes.add(createNewNode(ds, ll, cmds));
        }

        nodes.add(endNode);
        return nodes;
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
