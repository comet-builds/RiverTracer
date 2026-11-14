package org.openstreetmap.josm.plugins.rivertracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
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
    private static final double JOIN_DISTANCE_MULTIPLIER = 1.5;
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
        double snapDistMeters = computeSnapDistanceMeters(mv, options, startLL);

        ConnectionInfo startConn = analyzeConnection(ds, startLL, snapDistMeters, options);
        ConnectionInfo endConn = analyzeConnection(ds, endLL, snapDistMeters, options);

        List<Command> cmds = new ArrayList<>();

        try {
            boolean sameWayLoop = startConn.way != null && startConn.way == endConn.way;
            if (canMergeBoth(startConn, endConn)) {
                mergeBoth(ds, startConn, endConn, path, mv, cmds);
            } else if (!sameWayLoop && canMerge(startConn)) {
                mergeOne(ds, startConn, path, mv, cmds, true, snapDistMeters);
            } else if (!sameWayLoop && canMerge(endConn)) {
                mergeOne(ds, endConn, path, mv, cmds, false, snapDistMeters);
            } else {
                Node startNode = resolveEndpoint(ds, startConn, startLL, cmds, snapDistMeters);
                Node endNode = resolveEndpoint(ds, endConn, endLL, cmds, snapDistMeters);
                createFreshWay(ds, path, mv, cmds, options, startNode, endNode);
            }

            if (!cmds.isEmpty()) {
                Command finalCommand = new SequenceCommand(tr("Trace River"), cmds);
                UndoRedoHandler.getInstance().add(finalCommand);
                mv.repaint();
            }
        } catch (UserCancelException e) {
            // User cancelled the conflict dialog, abort operation
        } catch (Exception e) {
            Logging.error("Error building OSM way.", e);
        }
    }

    private ConnectionInfo analyzeConnection(DataSet ds, LatLon ll, double snapDistMeters, RiverTracingOptions options) {
        ConnectionInfo info = new ConnectionInfo();
        info.node = findSnapNode(ds, ll, snapDistMeters);
        if (info.node == null) {
            return info;
        }

        Way w = findSingleParentWaterway(info.node, options);
        if (w != null && isEndpointOfWay(info.node, w)) {
            info.way = w;
            info.isStart = w.getNode(0) == info.node;
            info.isEnd = !info.isStart;
        }
        return info;
    }

    private Way findSingleParentWaterway(Node node, RiverTracingOptions options) {
        List<Way> parentWaterways = node.getParentWays().stream()
            .filter(this::isOpenWaterway)
            .collect(Collectors.toList());

        if (parentWaterways.size() != 1) {
            return null;
        }
        Way w = parentWaterways.get(0);
        String expectedType = options.isRiver() ? "river" : "stream";
        return expectedType.equals(w.get(TAG_WATERWAY)) ? w : null;
    }

    private boolean isOpenWaterway(Way w) {
        return !w.isDeleted() && w.hasKey(TAG_WATERWAY) && w.getNodesCount() > 1 && !w.isClosed();
    }

    private boolean isEndpointOfWay(Node node, Way w) {
        return w.getNode(0) == node || w.getNode(w.getNodesCount() - 1) == node;
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

    private void mergeOne(DataSet ds, ConnectionInfo conn, List<Point> path, MapView mv, List<Command> cmds, boolean isStartConn, double snapDistMeters) {
        Way targetWay = conn.way;

        int otherEndIndex = isStartConn ? path.size() - 1 : 0;
        LatLon ll = mv.getLatLon(path.get(otherEndIndex).x, path.get(otherEndIndex).y);
        Node otherEndSnap = snapPoint(ds, ll, cmds, snapDistMeters);
        if (targetWay.containsNode(otherEndSnap)) {
            return;
        }

        List<Node> segmentNodes = generateSegmentNodes(ds, path, mv, cmds,
            isStartConn ? conn.node : otherEndSnap,
            isStartConn ? otherEndSnap : conn.node
        );

        List<Node> newNodes = new ArrayList<>(segmentNodes);
        if (!isStartConn) {
            Collections.reverse(newNodes);
        }
        newNodes.remove(0); // Remove the connection node

        List<Node> finalNodes = new ArrayList<>(targetWay.getNodes());
        if (conn.isStart) {
            Collections.reverse(newNodes);
            finalNodes.addAll(0, newNodes);
        } else if (conn.isEnd) {
            finalNodes.addAll(newNodes);
        }

        Way newWay = new Way(targetWay);
        newWay.setNodes(finalNodes);
        cmds.add(new ChangeCommand(targetWay, newWay));
    }

    private void mergeBoth(DataSet ds, ConnectionInfo start, ConnectionInfo end, List<Point> path, MapView mv, List<Command> cmds) throws UserCancelException {
        // Generate nodes for the bridge.
        List<Node> bridgeNodes = generateSegmentNodes(ds, path, mv, cmds, start.node, end.node);

        boolean isForward = start.isEnd && end.isStart;
        Way firstWay = isForward ? start.way : end.way;
        Way secondWay = isForward ? end.way : start.way;

        List<Node> midNodes = new ArrayList<>(bridgeNodes);
        if (!isForward) {
            Collections.reverse(midNodes);
        }
        midNodes = midNodes.subList(1, midNodes.size() - 1);

        List<Node> finalNodes = new ArrayList<>(firstWay.getNodes());
        finalNodes.addAll(midNodes);
        finalNodes.addAll(secondWay.getNodes());

        applyMerge(firstWay, secondWay, finalNodes, cmds);
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

    private Node resolveEndpoint(DataSet ds, ConnectionInfo conn, LatLon ll, List<Command> cmds, double snapDistMeters) {
        return conn.isValid() ? conn.node : snapPoint(ds, ll, cmds, snapDistMeters);
    }

    private void createFreshWay(DataSet ds, List<Point> path, MapView mv, List<Command> cmds, RiverTracingOptions options, Node startNode, Node endNode) {
        List<Node> nodes = generateSegmentNodes(ds, path, mv, cmds, startNode, endNode);
        if (nodes.size() < 2 || (startNode == endNode && nodes.size() < 3)) {
            return;
        }

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

    private double computeSnapDistanceMeters(MapView mv, RiverTracingOptions options, LatLon refLL) {
        Point p = mv.getPoint(refLL);
        int joinPixels = (int) Math.ceil(JOIN_DISTANCE_MULTIPLIER * options.getStepSize());
        LatLon offsetLL = mv.getLatLon(p.x + joinPixels, p.y);
        double pixelSnapMeters = refLL.greatCircleDistance(offsetLL);
        return Math.max(SNAP_DISTANCE_METERS, pixelSnapMeters);
    }

    private double computeSearchPaddingDeg(double snapDistMeters) {
        return Math.max(SEARCH_PADDING_DEG, (snapDistMeters / 111000.0) * 2.5);
    }

    private BBox createSearchBox(LatLon ll, double snapDistMeters) {
        double pad = computeSearchPaddingDeg(snapDistMeters);
        return new BBox(
            ll.getX() - pad,
            ll.getY() - pad,
            ll.getX() + pad,
            ll.getY() + pad
        );
    }

    private List<Node> generateSegmentNodes(DataSet ds, List<Point> path, MapView mv, List<Command> cmds, Node startNode, Node endNode) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(startNode);

        for (int i = 1; i < path.size() - 1; i++) {
            Point p = path.get(i);
            LatLon ll = mv.getLatLon(p.x, p.y);
            if (startNode.getCoor().greatCircleDistance(ll) < 0.25 || endNode.getCoor().greatCircleDistance(ll) < 0.25) {
                continue;
            }
            nodes.add(createNewNode(ds, ll, cmds));
        }

        if (nodes.get(nodes.size() - 1) != endNode) {
            nodes.add(endNode);
        }
        return nodes;
    }

    private Node snapPoint(DataSet ds, LatLon ll, List<Command> cmds, double snapDistMeters) {
        Node snapNode = findSnapNode(ds, ll, snapDistMeters);
        
        if (snapNode != null) {
            return snapNode;
        }

        Node segmentNode = snapToSegment(ds, ll, cmds, snapDistMeters);
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

    private boolean hasValidWaterwayParent(Node n) {
        for (Way w : n.getParentWays()) {
            if (!w.isDeleted() && w.hasKey(TAG_WATERWAY)) {
                return true;
            }
        }
        return false;
    }

    private Node findSnapNode(DataSet ds, LatLon ll, double snapDistMeters) {
        BBox searchBox = createSearchBox(ll, snapDistMeters);
        Collection<Node> candidates = ds.searchNodes(searchBox);

        Node bestNode = null;
        double minDist = Double.MAX_VALUE;

        for (Node n : candidates) {
            if (!n.isDeleted() && hasValidWaterwayParent(n)) {
                double dist = n.getCoor().greatCircleDistance(ll);
                if (dist < snapDistMeters && dist < minDist) {
                    minDist = dist;
                    bestNode = n;
                }
            }
        }
        return bestNode;
    }

    private static class SegmentMatch {
        WaySegment seg;
        LatLon proj;
        double dist = Double.MAX_VALUE;
    }

    private Node snapToSegment(DataSet ds, LatLon ll, List<Command> cmds, double snapDistMeters) {
        BBox searchBox = createSearchBox(ll, snapDistMeters);
        Collection<Way> ways = ds.searchWays(searchBox);

        SegmentMatch bestMatch = new SegmentMatch();

        for (Way w : ways) {
            if (!w.isDeleted() && w.hasKey(TAG_WATERWAY)) {
                updateBestSegmentMatch(w, ll, bestMatch, snapDistMeters);
            }
        }

        if (bestMatch.seg == null) return null;

        Node newNode = new Node(bestMatch.proj);
        cmds.add(new AddCommand(ds, newNode));

        Way w = bestMatch.seg.getWay();
        Way newWay = new Way(w);
        newWay.addNode(bestMatch.seg.getLowerIndex() + 1, newNode);

        cmds.add(new ChangeCommand(w, newWay));
        return newNode;
    }

    private void updateBestSegmentMatch(Way w, LatLon ll, SegmentMatch bestMatch, double snapDistMeters) {
        for (int i = 0; i < w.getNodesCount() - 1; i++) {
            checkSegmentMatch(w, i, ll, bestMatch, snapDistMeters);
        }
    }

    private void checkSegmentMatch(Way w, int i, LatLon ll, SegmentMatch bestMatch, double snapDistMeters) {
        Node n1 = w.getNode(i);
        Node n2 = w.getNode(i + 1);

        if (n1.isDeleted() || n2.isDeleted()) {
            return;
        }

        LatLon c1 = n1.getCoor();
        LatLon c2 = n2.getCoor();

        if (!isWithinBoundingBox(c1, c2, ll, snapDistMeters)) {
            return;
        }

        LatLon proj = getClosestPointOnSegment(c1, c2, ll);
        double dist = proj.greatCircleDistance(ll);

        if (dist < snapDistMeters && dist < bestMatch.dist) {
            bestMatch.dist = dist;
            bestMatch.seg = new WaySegment(w, i);
            bestMatch.proj = proj;
        }
    }

    private boolean isWithinBoundingBox(LatLon c1, LatLon c2, LatLon ll, double snapDistMeters) {
        double pad = computeSearchPaddingDeg(snapDistMeters);
        double segMinLat = Math.min(c1.lat(), c2.lat());
        double segMaxLat = Math.max(c1.lat(), c2.lat());
        double segMinLon = Math.min(c1.lon(), c2.lon());
        double segMaxLon = Math.max(c1.lon(), c2.lon());

        return !(segMaxLat < ll.lat() - pad || segMinLat > ll.lat() + pad ||
                 segMaxLon < ll.lon() - pad || segMinLon > ll.lon() + pad);
    }

    private LatLon getClosestPointOnSegment(LatLon p1, LatLon p2, LatLon p) {
        Projection proj = ProjectionRegistry.getProjection();
        EastNorth en1 = proj.latlon2eastNorth(p1);
        EastNorth en2 = proj.latlon2eastNorth(p2);
        EastNorth enP = proj.latlon2eastNorth(p);

        double dx = en2.east() - en1.east();
        double dy = en2.north() - en1.north();
        if (dx == 0 && dy == 0) return p1;

        double t = ((enP.east() - en1.east()) * dx + (enP.north() - en1.north()) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));

        return proj.eastNorth2latlon(en1.interpolate(en2, t));
    }
}
