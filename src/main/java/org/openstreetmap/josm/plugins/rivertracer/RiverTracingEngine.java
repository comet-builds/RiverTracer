package org.openstreetmap.josm.plugins.rivertracer;

import java.awt.Point;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RiverTracingEngine {

    private static final int EDGE_MARGIN = 10;
    private static final double SCAN_ARC_STANDARD = Math.PI / 3.0;
    private static final double SCAN_ARC_SHARP = Math.PI / 1.5;
    private static final double SCAN_ARC_BRIDGE = Math.PI / 6.0;
    private static final double ANGLE_SMOOTHING_FACTOR = 0.55;
    private static final double COLOR_ADAPTATION_RATE = 0.05;
    private static final double JOIN_DISTANCE_MULTIPLIER = 1.5;
    private static final double BRIDGE_SCAN_STEP_MULTIPLIER = 2.2;
    private static final double BRIDGE_SCAN_TOLERANCE_MULTIPLIER = 1.15;
    private static final double INITIAL_DIRECTION_ANGLE_STEP = Math.PI / 16.0;
    private static final double SCAN_ANGLE_STEP = Math.PI / 32.0;
    private static final double SCAN_ANGLE_PENALTY_WEIGHT = 10.0;
    private static final int CENTER_ADJUST_SCAN_RANGE = 100;
    private static final double COLOR_MATCH_TOLERANCE_MULTIPLIER = 1.1;

    private final RiverTracingOptions options;

    public RiverTracingEngine(RiverTracingOptions options) {
        this.options = options;
    }

    public List<Point> trace(BufferedImage snapshot, Point start, List<Line2D> existingWaterways) {
        if (snapshot == null || !isValidPoint(start.x, start.y, snapshot.getWidth(), snapshot.getHeight())) {
            return new ArrayList<>();
        }
        int w = snapshot.getWidth();
        int h = snapshot.getHeight();

        int rawStartColor = sampleColor(snapshot, start.x, start.y, w, h);
        double initialAngle = findInitialDirection(snapshot, start, rawStartColor);

        Point centeredStart = adjustToCenter(snapshot, start, initialAngle, rawStartColor, w, h, null);
        int startColor = sampleColor(snapshot, centeredStart.x, centeredStart.y, w, h);
        double primaryAngle = findInitialDirection(snapshot, centeredStart, startColor);
        double backwardAngle = findOppositeDirection(snapshot, centeredStart, startColor, primaryAngle);

        List<Point> forwardPath = findPath(snapshot, centeredStart, primaryAngle, existingWaterways, Collections.emptyList());
        List<Point> backwardPath = findPath(snapshot, centeredStart, backwardAngle, existingWaterways, forwardPath);

        List<Point> fullPath = new ArrayList<>(backwardPath);
        Collections.reverse(fullPath);

        if (!forwardPath.isEmpty()) {
            forwardPath.remove(0);
            fullPath.addAll(forwardPath);
        }

        if (options.getSmoothness() > 0) {
            smoothPath(snapshot, fullPath, options.getSmoothness());
        }

        return fullPath;
    }

    private List<Point> findPath(BufferedImage img, Point start, double startAngle, List<Line2D> waterways, List<Point> otherPath) {
        List<Point> path = new ArrayList<>();
        path.add(start);

        int width = img.getWidth();
        int height = img.getHeight();

        if (!isValidPoint(start.x, start.y, width, height)) return path;

        int initialColor = sampleColor(img, start.x, start.y, width, height);
        TracingContext ctx = new TracingContext(start, startAngle, initialColor);
        ctx.estimatedHalfWidth = estimateInitialHalfWidth(img, start, startAngle, initialColor, width, height);

        while (ctx.searching) {
            processNextStep(ctx, img, path, otherPath, waterways, width, height);
        }

        return path;
    }

    private void processNextStep(TracingContext ctx, BufferedImage img, List<Point> path, List<Point> otherPath, List<Line2D> waterways, int width, int height) {
        if (checkBoundary(ctx, width, height)) return;

        Point centeredNext = findNextPoint(ctx, img, path, width, height);
        if (centeredNext == null) return;

        if (intersectsOrRetraces(img, ctx, path, otherPath, centeredNext)) {
            ctx.searching = false;
            return;
        }

        if (attemptJoin(ctx, path, waterways, centeredNext)) return;

        if (shouldStopAtAngle(path, ctx.current, centeredNext)) {
            ctx.searching = false;
            return;
        }

        advanceContext(ctx, img, path, centeredNext);
    }

    private boolean checkBoundary(TracingContext ctx, int width, int height) {
        if (isAtEdge(ctx.current, width, height)) {
            ctx.searching = false;
            return true;
        }
        return false;
    }

    private Point findNextPoint(TracingContext ctx, BufferedImage img, List<Point> path, int width, int height) {
        ScanResult res = scanNextStep(img, ctx, path);
        if (res == null) {
            ctx.searching = false;
            return null;
        }
        ctx.lastScanAngle = res.angle;
        return adjustToCenter(img, res.point, res.angle, ctx.adaptiveTargetColor, width, height, ctx);
    }

    private boolean attemptJoin(TracingContext ctx, List<Point> path, List<Line2D> waterways, Point centeredNext) {
        double joinDistance = JOIN_DISTANCE_MULTIPLIER * options.getStepSize();
        JoinResult join = checkJoin(ctx.current, centeredNext, waterways, joinDistance, path.size());
        if (join != null) {
            path.add(join.point);
            ctx.searching = false;
            return true;
        }
        return false;
    }

    private void advanceContext(TracingContext ctx, BufferedImage img, List<Point> path, Point centeredNext) {
        path.add(centeredNext);

        double displacementAngle = Math.atan2((double) centeredNext.y - ctx.current.y, (double) centeredNext.x - ctx.current.x);
        ctx.lastStepAngle = displacementAngle;
        double effectiveAngle = interpolateAngle(ctx.lastScanAngle, displacementAngle, 0.65);
        ctx.smoothedAngle = interpolateAngle(ctx.smoothedAngle, effectiveAngle, ANGLE_SMOOTHING_FACTOR);

        ctx.current = centeredNext;
        int newColor = sampleColor(img, ctx.current.x, ctx.current.y, img.getWidth(), img.getHeight());

        double maxJump = options.getMaxJump();
        double initialDiffSq = colorDistanceSq(ctx.initialColor, newColor);
        if (initialDiffSq > maxJump * maxJump) {
            ctx.searching = false;
        } else {
            double tol = options.getColorTolerance();
            double stepDiff = Math.sqrt(colorDistanceSq(ctx.adaptiveTargetColor, newColor));
            double confidence = Math.max(0.15, 1.0 - (stepDiff / (tol * 1.5)));
            ctx.adaptColor(newColor, COLOR_ADAPTATION_RATE * confidence);
        }
    }

    private ScanResult scanNextStep(BufferedImage img, TracingContext ctx, List<Point> path) {
        ScanResult res = scanChannel(img, path, ctx);
        if (res != null) {
            return res;
        }
        return scanBridge(img, path, ctx);
    }

    private boolean shouldStopAtAngle(List<Point> path, Point current, Point next) {
        int n = path.size();
        if (n < 2) return false;
        Point prev = path.get(n - 2);

        double angle1 = Math.atan2((double) current.y - prev.y, (double) current.x - prev.x);
        double angle2 = Math.atan2((double) next.y - current.y, (double) next.x - current.x);

        if (Math.abs(Math.toDegrees(normalizeAngle(angle2 - angle1))) >= options.getMaxAngle()) {
            return true;
        }

        // Prevent two consecutive sharp steps in the same direction from forming a 180-degree U-turn inside the channel
        if (n >= 3) {
            Point prev2 = path.get(n - 3);
            double angle0 = Math.atan2((double) prev.y - prev2.y, (double) prev.x - prev2.x);
            double turn1 = normalizeAngle(angle1 - angle0);
            double turn2 = normalizeAngle(angle2 - angle1);
            if (turn1 * turn2 > 0) {
                double cumulativeDeg = Math.abs(Math.toDegrees(turn1 + turn2));
                if (cumulativeDeg >= Math.min(160.0, options.getMaxAngle() * 1.25)
                        && prev2.distance(next) < options.getStepSize() * 1.5) {
                    return true;
                }
            }
        }

        return false;
    }

    private static double normalizeAngle(double diff) {
        while (diff <= -Math.PI) diff += 2 * Math.PI;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        return diff;
    }

    private void smoothPath(BufferedImage img, List<Point> path, int iterations) {
        int n = path.size();
        if (n < 3) return;
        int w = img.getWidth();
        int h = img.getHeight();

        double[] x = new double[n];
        double[] y = new double[n];
        double[] nextX = new double[n];
        double[] nextY = new double[n];
        int[] localWaterColors = new int[n];

        for (int i = 0; i < n; i++) {
            Point p = path.get(i);
            x[i] = p.x;
            y[i] = p.y;
            nextX[i] = p.x;
            nextY[i] = p.y;
            localWaterColors[i] = sampleColor(img, p.x, p.y, w, h);
        }

        for (int iter = 0; iter < iterations; iter++) {
            for (int i = 1; i < n - 1; i++) {
                double candX = (x[i - 1] + 2.0 * x[i] + x[i + 1]) / 4.0;
                double candY = (y[i - 1] + 2.0 * y[i] + y[i + 1]) / 4.0;
                int ix = (int) Math.round(candX);
                int iy = (int) Math.round(candY);
                if (isColorMatch(img, ix, iy, localWaterColors[i], w, h)) {
                    nextX[i] = candX;
                    nextY[i] = candY;
                } else {
                    nextX[i] = x[i];
                    nextY[i] = y[i];
                }
            }
            double[] tempX = x;
            x = nextX;
            nextX = tempX;

            double[] tempY = y;
            y = nextY;
            nextY = tempY;
        }

        for (int i = 0; i < n; i++) {
            path.set(i, new Point((int) Math.round(x[i]), (int) Math.round(y[i])));
        }
    }

    private double findInitialDirection(BufferedImage img, Point p, int target) {
        double bestAngle = 0;
        double minScore = Double.MAX_VALUE;
        int stepSize = options.getStepSize();
        int w = img.getWidth();
        int h = img.getHeight();
        int maxReach = Math.min(CENTER_ADJUST_SCAN_RANGE, Math.max(stepSize * 3, 45));

        for (double angle = 0; angle < 2 * Math.PI; angle += INITIAL_DIRECTION_ANGLE_STEP) {
            int forwardReach = measureBankDistance(img, p, angle, target, maxReach, w, h);
            int backwardReach = measureBankDistance(img, p, angle + Math.PI, target, maxReach, w, h);
            int perpReach = measureBankDistance(img, p, angle + Math.PI / 2.0, target, maxReach, w, h)
                          + measureBankDistance(img, p, angle - Math.PI / 2.0, target, maxReach, w, h);

            int probeDist = Math.max(stepSize, Math.min(maxReach, perpReach));
            double forwardDiff = sampleRayColorDiff(img, p, angle, probeDist, target, w, h);
            if (Double.isInfinite(forwardDiff)) continue;

            double backwardDiff = sampleRayColorDiff(img, p, angle + Math.PI, probeDist, target, w, h);
            if (Double.isInfinite(backwardDiff)) {
                backwardDiff = forwardDiff * 1.5;
            }

            int alongReach = forwardReach + backwardReach;
            double elongationBonus = Math.max(-25.0, Math.min(25.0, ((double) perpReach - alongReach) * 0.5));
            double score = forwardDiff + 0.5 * backwardDiff + elongationBonus;

            if (score < minScore) {
                minScore = score;
                bestAngle = angle;
            }
        }
        return bestAngle;
    }

    private double findOppositeDirection(BufferedImage img, Point p, int target, double primaryAngle) {
        double nominalOpposite = primaryAngle + Math.PI;
        double bestAngle = nominalOpposite;
        double minScore = Double.MAX_VALUE;
        int stepSize = options.getStepSize();
        int w = img.getWidth();
        int h = img.getHeight();
        int maxReach = Math.min(CENTER_ADJUST_SCAN_RANGE, Math.max(stepSize * 3, 45));
        double searchArc = Math.PI / 3.0;

        for (double angle = nominalOpposite - searchArc; angle <= nominalOpposite + searchArc; angle += INITIAL_DIRECTION_ANGLE_STEP) {
            double rayDiff = sampleRayColorDiff(img, p, angle, stepSize, target, w, h);
            if (Double.isInfinite(rayDiff)) continue;

            int reach = measureBankDistance(img, p, angle, target, maxReach, w, h);
            double deviationPenalty = Math.abs(normalizeAngle(angle - nominalOpposite)) * 8.0;
            double score = rayDiff + deviationPenalty - Math.min(20.0, reach * 0.4);
            if (score < minScore) {
                minScore = score;
                bestAngle = angle;
            }
        }
        return bestAngle;
    }

    private double sampleRayColorDiff(BufferedImage img, Point p, double angle, int stepSize, int target, int w, int h) {
        double[] fractions = {0.35, 0.7, 1.0};
        double sumDiff = 0.0;
        int count = 0;

        for (double frac : fractions) {
            int r = Math.max(1, (int) Math.round(stepSize * frac));
            int nx = (int) Math.round(p.x + Math.cos(angle) * r);
            int ny = (int) Math.round(p.y + Math.sin(angle) * r);

            if (!isValidPoint(nx, ny, w, h)) continue;

            int c = sampleColor(img, nx, ny, w, h);
            sumDiff += Math.sqrt(colorDistanceSq(target, c));
            count++;
        }

        return count > 0 ? sumDiff / count : Double.POSITIVE_INFINITY;
    }

    private JoinResult checkJoin(Point current, Point p, List<Line2D> waterways, double threshold, int pathSize) {
        double thresholdSq = threshold * threshold;

        JoinResult intersectionJoin = checkSegmentIntersectionJoin(current, p, waterways, thresholdSq, pathSize);
        if (intersectionJoin != null) {
            return intersectionJoin;
        }

        JoinResult endpointJoin = checkEndpointJoin(current, p, waterways, thresholdSq, pathSize);
        if (endpointJoin != null) {
            return endpointJoin;
        }

        return checkProjectionJoin(current, p, waterways, thresholdSq, pathSize);
    }

    private JoinResult checkEndpointJoin(Point current, Point p, List<Line2D> waterways, double thresholdSq, int pathSize) {
        double minEndDSq = thresholdSq;
        Point bestEndP = null;

        for (Line2D l : waterways) {
            Point end1 = new Point((int) Math.round(l.getX1()), (int) Math.round(l.getY1()));
            double d1Sq = p.distanceSq(end1);
            if (d1Sq < minEndDSq && isValidJoinCandidate(current, p, end1, d1Sq, pathSize)) {
                minEndDSq = d1Sq;
                bestEndP = end1;
            }

            Point end2 = new Point((int) Math.round(l.getX2()), (int) Math.round(l.getY2()));
            double d2Sq = p.distanceSq(end2);
            if (d2Sq < minEndDSq && isValidJoinCandidate(current, p, end2, d2Sq, pathSize)) {
                minEndDSq = d2Sq;
                bestEndP = end2;
            }
        }

        return bestEndP != null ? new JoinResult(bestEndP, Math.sqrt(minEndDSq)) : null;
    }

    private JoinResult checkProjectionJoin(Point current, Point p, List<Line2D> waterways, double thresholdSq, int pathSize) {
        double minSegDSq = thresholdSq;
        Point bestProjP = null;
        for (Line2D l : waterways) {
            double dSq = l.ptSegDistSq(p.x, p.y);
            if (dSq < minSegDSq) {
                Point proj = getProjectedPoint(l, p);
                if (isValidJoinCandidate(current, p, proj, dSq, pathSize)) {
                    minSegDSq = dSq;
                    bestProjP = proj;
                }
            }
        }
        return bestProjP != null ? new JoinResult(bestProjP, Math.sqrt(minSegDSq)) : null;
    }

    private JoinResult checkSegmentIntersectionJoin(Point current, Point next, List<Line2D> waterways, double endSnapThresholdSq, int pathSize) {
        double minDistSq = Double.MAX_VALUE;
        Point bestJoinPoint = null;

        for (Line2D l : waterways) {
            if (isStartingOnSegment(current, l, pathSize)
                    || !Line2D.linesIntersect(current.x, current.y, next.x, next.y, l.getX1(), l.getY1(), l.getX2(), l.getY2())) {
                continue;
            }
            Point inter = computeLineIntersection(current, next, l);
            if (inter != null) {
                inter = snapIntersectionToEndpoint(inter, l, endSnapThresholdSq);
                double dSq = current.distanceSq(inter);
                if (dSq < minDistSq) {
                    minDistSq = dSq;
                    bestJoinPoint = inter;
                }
            }
        }

        return bestJoinPoint != null ? new JoinResult(bestJoinPoint, Math.sqrt(minDistSq)) : null;
    }

    private boolean isStartingOnSegment(Point current, Line2D l, int pathSize) {
        return pathSize <= 1 && l.ptSegDistSq(current.x, current.y) < 9.0;
    }

    private Point snapIntersectionToEndpoint(Point inter, Line2D l, double endSnapThresholdSq) {
        Point end1 = new Point((int) Math.round(l.getX1()), (int) Math.round(l.getY1()));
        Point end2 = new Point((int) Math.round(l.getX2()), (int) Math.round(l.getY2()));
        double d1Sq = inter.distanceSq(end1);
        double d2Sq = inter.distanceSq(end2);
        if (d1Sq < endSnapThresholdSq && d1Sq <= d2Sq) {
            return end1;
        }
        if (d2Sq < endSnapThresholdSq) {
            return end2;
        }
        return inter;
    }

    private Point computeLineIntersection(Point p1, Point p2, Line2D l) {
        double x1 = p1.x;
        double y1 = p1.y;
        double x2 = p2.x;
        double y2 = p2.y;
        double x3 = l.getX1();
        double y3 = l.getY1();
        double x4 = l.getX2();
        double y4 = l.getY2();

        double denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(denom) < 1e-6) return null;

        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
        t = Math.max(0.0, Math.min(1.0, t));

        return new Point((int) Math.round(x1 + t * (x2 - x1)), (int) Math.round(y1 + t * (y2 - y1)));
    }

    private boolean isValidJoinCandidate(Point current, Point next, Point candidate, double nextDistSq, int pathSize) {
        double stepDx = (double) next.x - current.x;
        double stepDy = (double) next.y - current.y;
        double candDx = (double) candidate.x - current.x;
        double candDy = (double) candidate.y - current.y;

        // Candidate must lie ahead of current along the step direction
        double dot = stepDx * candDx + stepDy * candDy;
        if (dot <= 0) {
            return false;
        }

        double currentDistSq = candDx * candDx + candDy * candDy;
        return pathSize > 2 || nextDistSq < currentDistSq * 0.85;
    }

    private Point getProjectedPoint(Line2D l, Point p) {
        double x1 = l.getX1();
        double y1 = l.getY1();
        double x2 = l.getX2();
        double y2 = l.getY2();
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0 && dy == 0) return new Point((int) Math.round(x1), (int) Math.round(y1));

        double t = ((p.x - x1) * dx + (p.y - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));

        return new Point((int) Math.round(x1 + t * dx), (int) Math.round(y1 + t * dy));
    }

    private boolean intersectsOrRetraces(BufferedImage img, TracingContext ctx, List<Point> path, List<Point> otherPath, Point next) {
        if (path.isEmpty()) return false;
        Point current = path.get(path.size() - 1);
        if (current.equals(next)) return true;

        double minClearance = Math.max(6.0, options.getStepSize() * 0.55);
        double minClearanceSq = minClearance * minClearance;
        double sameChannelDist = computeSameChannelDist(ctx, minClearance);
        double sameChannelDistSq = sameChannelDist * sameChannelDist;

        return intersectsCurrentPath(img, ctx, path, next, minClearanceSq, sameChannelDistSq)
                || intersectsOtherPath(img, ctx, otherPath, next, minClearanceSq, sameChannelDistSq);
    }

    private double computeSameChannelDist(TracingContext ctx, double minClearance) {
        if (ctx.estimatedHalfWidth <= 0) {
            return minClearance;
        }
        return Math.max(minClearance, Math.min(options.getStepSize() * 1.4, ctx.estimatedHalfWidth * 1.8));
    }

    private boolean intersectsCurrentPath(BufferedImage img, TracingContext ctx, List<Point> path, Point next, double minClearanceSq, double sameChannelDistSq) {
        Point current = ctx.current;
        for (int i = 0; i < path.size() - 2; i++) {
            Point p1 = path.get(i);
            Point p2 = path.get(i + 1);
            if (Line2D.linesIntersect(p1.x, p1.y, p2.x, p2.y, current.x, current.y, next.x, next.y)) {
                return true;
            }
            if (i <= path.size() - 4) {
                double distSq = Line2D.ptSegDistSq(p1.x, p1.y, p2.x, p2.y, next.x, next.y);
                if (distSq < minClearanceSq || (i <= path.size() - 6 && distSq < sameChannelDistSq && isSameChannelReverse(img, ctx, next, p1, p2, true))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean intersectsOtherPath(BufferedImage img, TracingContext ctx, List<Point> otherPath, Point next, double minClearanceSq, double sameChannelDistSq) {
        Point current = ctx.current;
        for (int i = 1; i < otherPath.size() - 1; i++) {
            Point p1 = otherPath.get(i);
            Point p2 = otherPath.get(i + 1);
            if (Line2D.linesIntersect(p1.x, p1.y, p2.x, p2.y, current.x, current.y, next.x, next.y)) {
                return true;
            }
            if (i >= 2) {
                double distSq = Line2D.ptSegDistSq(p1.x, p1.y, p2.x, p2.y, next.x, next.y);
                if (distSq < minClearanceSq || (i >= 3 && distSq < sameChannelDistSq && isSameChannelReverse(img, ctx, next, p1, p2, false))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameChannelReverse(BufferedImage img, TracingContext ctx, Point next, Point p1, Point p2, boolean sameHalfPath) {
        Point current = ctx.current;
        double dx1 = (double) next.x - current.x;
        double dy1 = (double) next.y - current.y;
        double dx2 = (double) p2.x - p1.x;
        double dy2 = (double) p2.y - p1.y;
        double len1 = Math.hypot(dx1, dy1);
        double len2 = Math.hypot(dx2, dy2);
        if (len1 < 1e-3 || len2 < 1e-3) return false;

        double cos = (dx1 * dx2 + dy1 * dy2) / (len1 * len2);
        boolean oppositeFlow = sameHalfPath ? (cos < -0.5) : (cos > 0.5);
        if (!oppositeFlow) return false;

        Point proj = getProjectedPoint(new Line2D.Double(p1.x, p1.y, p2.x, p2.y), next);
        int midX = (next.x + proj.x) / 2;
        int midY = (next.y + proj.y) / 2;
        return isColorMatch(img, midX, midY, ctx.adaptiveTargetColor, img.getWidth(), img.getHeight());
    }

    private boolean isValidPoint(int x, int y, int w, int h) {
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    private boolean isAtEdge(Point p, int w, int h) {
        return p.x <= EDGE_MARGIN || p.y <= EDGE_MARGIN || p.x >= w - EDGE_MARGIN || p.y >= h - EDGE_MARGIN;
    }

    private boolean isBacktracking(List<Point> path, Point current, Point candidate) {
        int n = path.size();
        if (n < 2) return false;
        Point prev = path.get(n - 2);
        double stepDistSq = current.distanceSq(candidate);
        // Reject if the candidate ends up closer to the previous vertex than 70% of the step length
        return candidate.distanceSq(prev) < stepDistSq * 0.55;
    }

    private ScanResult scanChannel(BufferedImage img, List<Point> path, TracingContext ctx) {
        int fullStep = options.getStepSize();
        int minStep = Math.max(7, (int) Math.round(fullStep * 0.6));
        double tol = options.getColorTolerance();
        double baseTolSq = tol * tol;
        double sharpTolSq = (tol * 1.15) * (tol * 1.15);
        double maxTurnRad = Math.toRadians(options.getMaxAngle());
        double scanArc = Math.min(SCAN_ARC_SHARP, maxTurnRad);

        Point bestPoint = null;
        double minScore = Double.MAX_VALUE;
        double bestAngleFound = ctx.lastStepAngle;

        for (double angle = ctx.lastStepAngle - scanArc; angle <= ctx.lastStepAngle + scanArc; angle += SCAN_ANGLE_STEP) {
            if (!isAllowedTurnAngle(path, ctx, angle, maxTurnRad)) {
                continue;
            }

            double smoothDiff = Math.abs(normalizeAngle(angle - ctx.smoothedAngle));
            boolean isSharpRay = smoothDiff > SCAN_ARC_STANDARD;
            double allowedTolSq = isSharpRay ? sharpTolSq : baseTolSq;

            RayProbe probe = traceChannelRay(img, ctx, angle, fullStep, allowedTolSq);
            if (probe.reachedR >= minStep && probe.endDiffSq < allowedTolSq && !isBacktracking(path, ctx.current, probe.endpoint)) {
                double totalScore = scoreChannelRay(img, ctx, probe, angle, smoothDiff, isSharpRay, allowedTolSq);
                if (totalScore < minScore) {
                    minScore = totalScore;
                    bestPoint = probe.endpoint;
                    bestAngleFound = angle;
                }
            }
        }

        return bestPoint != null ? new ScanResult(bestPoint, bestAngleFound) : null;
    }

    private boolean isAllowedTurnAngle(List<Point> path, TracingContext ctx, double angle, double maxTurnRad) {
        return path.size() < 2 || Math.abs(normalizeAngle(angle - ctx.lastStepAngle)) < maxTurnRad;
    }

    private RayProbe traceChannelRay(BufferedImage img, TracingContext ctx, double angle, int fullStep, double allowedTolSq) {
        Point center = ctx.current;
        int targetColor = ctx.adaptiveTargetColor;
        int w = img.getWidth();
        int h = img.getHeight();
        double rayDiffSum = 0.0;
        int raySamples = 0;
        int reachedR = 0;
        double endDiffSq = Double.MAX_VALUE;

        for (int r = 2; r <= fullStep; r += 2) {
            double rDiffSq = sampleRayDistanceDiffSq(img, center, angle, r, targetColor, w, h);
            if (rDiffSq >= allowedTolSq * 1.2) {
                break;
            }
            rayDiffSum += Math.sqrt(rDiffSq);
            raySamples++;
            reachedR = r;
            endDiffSq = rDiffSq;
        }

        if (reachedR == fullStep - 1) {
            double fullStepDiffSq = sampleRayDistanceDiffSq(img, center, angle, fullStep, targetColor, w, h);
            if (fullStepDiffSq < allowedTolSq) {
                reachedR = fullStep;
                endDiffSq = fullStepDiffSq;
            }
        }

        Point endpoint = new Point(
                (int) Math.round(center.x + Math.cos(angle) * reachedR),
                (int) Math.round(center.y + Math.sin(angle) * reachedR));
        double meanRayDiff = raySamples > 0 ? (rayDiffSum / raySamples) : Math.sqrt(endDiffSq);
        return new RayProbe(endpoint, reachedR, endDiffSq, meanRayDiff);
    }

    private double sampleRayDistanceDiffSq(BufferedImage img, Point center, double angle, int r, int targetColor, int w, int h) {
        int rx = (int) Math.round(center.x + Math.cos(angle) * r);
        int ry = (int) Math.round(center.y + Math.sin(angle) * r);
        if (!isValidPoint(rx, ry, w, h)) {
            return Double.MAX_VALUE;
        }
        return colorDistanceSq(targetColor, sampleColor(img, rx, ry, w, h));
    }

    private double scoreChannelRay(BufferedImage img, TracingContext ctx, RayProbe probe, double angle, double smoothDiff, boolean isSharpRay, double allowedTolSq) {
        int fullStep = options.getStepSize();
        double colorScore = 0.6 * Math.sqrt(probe.endDiffSq) + 0.4 * probe.meanRayDiff;
        double anglePenalty = smoothDiff * SCAN_ANGLE_PENALTY_WEIGHT
                + (isSharpRay ? (smoothDiff - SCAN_ARC_STANDARD) * 12.0 : 0.0);
        double shortStepPenalty = ((double) fullStep - probe.reachedR) * 1.4;
        double clearanceBonus = computeBankClearanceBonus(img, ctx, probe.endpoint.x, probe.endpoint.y, angle, allowedTolSq);
        return Math.max(0.0, colorScore + anglePenalty + shortStepPenalty - clearanceBonus);
    }

    private ScanResult scanBridge(BufferedImage img, List<Point> path, TracingContext ctx) {
        Point center = ctx.current;
        int bridgeRadius = (int) Math.round(options.getStepSize() * BRIDGE_SCAN_STEP_MULTIPLIER);
        int verifyRadius = bridgeRadius + Math.max(5, options.getStepSize() / 2);
        int maxObstacleSpan = (int) Math.round(options.getStepSize() * 1.7);
        double tol = options.getColorTolerance() * BRIDGE_SCAN_TOLERANCE_MULTIPLIER;
        double tolSq = tol * tol;
        double maxTurnRad = Math.toRadians(options.getMaxAngle());
        double scanArc = Math.min(SCAN_ARC_BRIDGE, maxTurnRad);

        Point bestPoint = null;
        double minScore = Double.MAX_VALUE;
        double bestAngleFound = ctx.lastStepAngle;

        for (double angle = ctx.lastStepAngle - scanArc; angle <= ctx.lastStepAngle + scanArc; angle += SCAN_ANGLE_STEP) {
            Point candidate = new Point(
                    (int) Math.round(center.x + Math.cos(angle) * bridgeRadius),
                    (int) Math.round(center.y + Math.sin(angle) * bridgeRadius));
            if (isBacktracking(path, center, candidate)
                    || countObstaclePixels(img, ctx, angle, bridgeRadius, tolSq) > maxObstacleSpan) {
                continue;
            }

            double farColorScore = evaluateBridgeFarSide(img, ctx, angle, bridgeRadius, verifyRadius, tolSq);
            if (!Double.isInfinite(farColorScore)) {
                double anglePenalty = Math.abs(normalizeAngle(angle - ctx.lastStepAngle)) * SCAN_ANGLE_PENALTY_WEIGHT;
                double clearanceBonus = computeBankClearanceBonus(img, ctx, candidate.x, candidate.y, angle, tolSq);
                double totalScore = Math.max(0.0, farColorScore + anglePenalty - clearanceBonus);

                if (totalScore < minScore) {
                    minScore = totalScore;
                    bestPoint = candidate;
                    bestAngleFound = angle;
                }
            }
        }

        return bestPoint != null ? new ScanResult(bestPoint, bestAngleFound) : null;
    }

    private int countObstaclePixels(BufferedImage img, TracingContext ctx, double angle, int bridgeRadius, double tolSq) {
        Point center = ctx.current;
        int w = img.getWidth();
        int h = img.getHeight();
        int obstaclePixels = 0;
        for (int r = 2; r < bridgeRadius; r += 2) {
            if (sampleRayDistanceDiffSq(img, center, angle, r, ctx.adaptiveTargetColor, w, h) >= tolSq) {
                obstaclePixels += 2;
            }
        }
        return obstaclePixels;
    }

    private double evaluateBridgeFarSide(BufferedImage img, TracingContext ctx, double angle, int bridgeRadius, int verifyRadius, double tolSq) {
        Point center = ctx.current;
        int w = img.getWidth();
        int h = img.getHeight();
        int nx = (int) Math.round(center.x + Math.cos(angle) * bridgeRadius);
        int ny = (int) Math.round(center.y + Math.sin(angle) * bridgeRadius);
        int vx = (int) Math.round(center.x + Math.cos(angle) * verifyRadius);
        int vy = (int) Math.round(center.y + Math.sin(angle) * verifyRadius);
        if (!isValidPoint(nx, ny, w, h) || !isValidPoint(vx, vy, w, h)) {
            return Double.POSITIVE_INFINITY;
        }

        int c1 = sampleColor(img, nx, ny, w, h);
        int c2 = sampleColor(img, vx, vy, w, h);
        double d1Sq = colorDistanceSq(ctx.adaptiveTargetColor, c1);
        double d2Sq = colorDistanceSq(ctx.adaptiveTargetColor, c2);
        double maxJumpSq = options.getMaxJump() * options.getMaxJump();

        if (d1Sq >= tolSq || d2Sq >= tolSq
                || colorDistanceSq(ctx.initialColor, c1) >= maxJumpSq
                || colorDistanceSq(ctx.initialColor, c2) >= maxJumpSq) {
            return Double.POSITIVE_INFINITY;
        }
        return 0.6 * Math.sqrt(d1Sq) + 0.4 * Math.sqrt(d2Sq);
    }

    private double computeBankClearanceBonus(BufferedImage img, TracingContext ctx, int x, int y, double angle, double toleranceSq) {
        double perpAngle = angle + Math.PI / 2.0;
        double hw = ctx.estimatedHalfWidth;
        int targetColor = ctx.adaptiveTargetColor;
        int innerDist = hw > 0 ? Math.max(2, Math.min(6, (int) Math.round(hw * 0.4))) : 3;
        int outerDist = hw > 4 ? Math.max(4, Math.min(12, (int) Math.round(hw * 0.75))) : 0;

        double bonus = 0.0;
        if (hasWaterAtOffset(img, x, y, perpAngle, innerDist, targetColor, toleranceSq)) {
            bonus += 2.0;
        }
        if (hasWaterAtOffset(img, x, y, perpAngle, -innerDist, targetColor, toleranceSq)) {
            bonus += 2.0;
        }
        if (outerDist > innerDist
                && hasWaterAtOffset(img, x, y, perpAngle, outerDist, targetColor, toleranceSq)
                && hasWaterAtOffset(img, x, y, perpAngle, -outerDist, targetColor, toleranceSq)) {
            bonus += 2.0;
        }
        return bonus;
    }

    private boolean hasWaterAtOffset(BufferedImage img, int x, int y, double perpAngle, int offset, int targetColor, double toleranceSq) {
        int ox = (int) Math.round(x + Math.cos(perpAngle) * offset);
        int oy = (int) Math.round(y + Math.sin(perpAngle) * offset);
        int w = img.getWidth();
        int h = img.getHeight();
        return isValidPoint(ox, oy, w, h) && colorDistanceSq(targetColor, sampleColor(img, ox, oy, w, h)) < toleranceSq;
    }

    private double estimateInitialHalfWidth(BufferedImage img, Point p, double angle, int target, int w, int h) {
        double perpAngle = angle + Math.PI / 2.0;
        int scanDist = CENTER_ADJUST_SCAN_RANGE;
        int leftLimit = measureBankDistance(img, p, perpAngle, target, scanDist, w, h);
        int rightLimit = measureBankDistance(img, p, perpAngle + Math.PI, target, scanDist, w, h);
        if (leftLimit >= scanDist - 1 || rightLimit >= scanDist - 1) {
            return -1.0;
        }
        return Math.max(1.0, ((double) leftLimit + rightLimit) / 2.0);
    }

    private int measureBankDistance(BufferedImage img, Point p, double dirAngle, int target, int scanDist, int w, int h) {
        int limit = 0;
        int glitchesSkipped = 0;
        for (int r = 0; r < scanDist; r++) {
            int nx = (int) Math.round(p.x + Math.cos(dirAngle) * r);
            int ny = (int) Math.round(p.y + Math.sin(dirAngle) * r);
            if (!isColorMatch(img, nx, ny, target, w, h)) {
                if (glitchesSkipped >= 1 || r + 1 >= scanDist || !isRayPointMatch(img, p, dirAngle, r + 1, target, w, h)) {
                    limit = r;
                    break;
                }
                glitchesSkipped++;
            }
            limit = r;
        }
        return limit;
    }

    private boolean isRayPointMatch(BufferedImage img, Point p, double dirAngle, int r, int target, int w, int h) {
        int nextX = (int) Math.round(p.x + Math.cos(dirAngle) * r);
        int nextY = (int) Math.round(p.y + Math.sin(dirAngle) * r);
        return isColorMatch(img, nextX, nextY, target, w, h);
    }

    private Point adjustToCenter(BufferedImage img, Point p, double angle, int target, int w, int h, TracingContext ctx) {
        double perpAngle = angle + Math.PI / 2.0;
        int scanDist = CENTER_ADJUST_SCAN_RANGE;
        int leftLimit = measureBankDistance(img, p, perpAngle, target, scanDist, w, h);
        int rightLimit = measureBankDistance(img, p, perpAngle + Math.PI, target, scanDist, w, h);

        if (leftLimit >= scanDist - 1 && rightLimit >= scanDist - 1) return p;

        if (ctx != null) {
            leftLimit = clampBankBlowout(leftLimit, rightLimit, ctx.estimatedHalfWidth);
            rightLimit = clampBankBlowout(rightLimit, leftLimit, ctx.estimatedHalfWidth);
            updateEstimatedWidth(ctx, leftLimit, rightLimit, scanDist);
        }

        double targetShift = ((double) leftLimit - rightLimit) / 2.0;
        return findCenteredWaterPoint(img, p, perpAngle, targetShift, target, w, h);
    }

    private int clampBankBlowout(int primaryLimit, int oppositeLimit, double estimatedHalfWidth) {
        if (estimatedHalfWidth <= 0) return primaryLimit;
        double maxAllowed = Math.max(estimatedHalfWidth * 2.2, estimatedHalfWidth + options.getStepSize());
        if (primaryLimit > maxAllowed && oppositeLimit <= maxAllowed) {
            return (int) Math.round(Math.min(primaryLimit, Math.max(oppositeLimit, estimatedHalfWidth * 1.4)));
        }
        return primaryLimit;
    }

    private void updateEstimatedWidth(TracingContext ctx, int leftLimit, int rightLimit, int scanDist) {
        if (leftLimit >= scanDist - 1 || rightLimit >= scanDist - 1) return;
        double observedHalfWidth = Math.max(1.0, ((double) leftLimit + rightLimit) / 2.0);
        if (ctx.estimatedHalfWidth <= 0) {
            ctx.estimatedHalfWidth = observedHalfWidth;
        } else {
            ctx.estimatedHalfWidth = ctx.estimatedHalfWidth * 0.8 + observedHalfWidth * 0.2;
        }
    }

    private Point findCenteredWaterPoint(BufferedImage img, Point p, double perpAngle, double targetShift, int target, int w, int h) {
        int maxSearchOffset = (int) Math.ceil(Math.abs(targetShift));
        for (int delta = 0; delta <= maxSearchOffset; delta++) {
            double shift = targetShift >= 0 ? Math.max(0.0, targetShift - delta) : Math.min(0.0, targetShift + delta);
            int cx = Math.max(0, Math.min(w - 1, (int) Math.round(p.x + Math.cos(perpAngle) * shift)));
            int cy = Math.max(0, Math.min(h - 1, (int) Math.round(p.y + Math.sin(perpAngle) * shift)));
            if (isColorMatch(img, cx, cy, target, w, h)) {
                return new Point(cx, cy);
            }
        }
        return p;
    }

    private boolean isColorMatch(BufferedImage img, int x, int y, int target, int w, int h) {
        if (!isValidPoint(x, y, w, h)) return false;
        double tol = options.getColorTolerance() * COLOR_MATCH_TOLERANCE_MULTIPLIER;
        return colorDistanceSq(target, sampleColor(img, x, y, w, h)) < tol * tol;
    }

    private static int sampleColor(BufferedImage img, int cx, int cy, int w, int h) {
        int rSum = 0;
        int gSum = 0;
        int bSum = 0;
        int weightSum = 0;

        for (int dy = -1; dy <= 1; dy++) {
            int y = Math.max(0, Math.min(h - 1, cy + dy));
            for (int dx = -1; dx <= 1; dx++) {
                int x = Math.max(0, Math.min(w - 1, cx + dx));
                int weight = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
                int rgb = img.getRGB(x, y);
                rSum += ((rgb >> 16) & 0xFF) * weight;
                gSum += ((rgb >> 8) & 0xFF) * weight;
                bSum += (rgb & 0xFF) * weight;
                weightSum += weight;
            }
        }

        int r = (rSum + (weightSum >> 1)) / weightSum;
        int g = (gSum + (weightSum >> 1)) / weightSum;
        int b = (bSum + (weightSum >> 1)) / weightSum;
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private double interpolateAngle(double oldAngle, double newAngle, double factor) {
        double diff = newAngle - oldAngle;
        while (diff <= -Math.PI) diff += 2 * Math.PI;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        return oldAngle + diff * factor;
    }

    private static double colorDistanceSq(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF;
        int g1 = (rgb1 >> 8) & 0xFF;
        int b1 = rgb1 & 0xFF;

        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;

        double dr = (double) r1 - r2;
        double dg = (double) g1 - g2;
        double db = (double) b1 - b2;

        return (2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db) / 3.0;
    }

    private static class ScanResult {
        final Point point;
        final double angle;
        ScanResult(Point p, double a) {
            this.point = p;
            this.angle = a;
        }
    }

    private static class RayProbe {
        final Point endpoint;
        final int reachedR;
        final double endDiffSq;
        final double meanRayDiff;

        RayProbe(Point endpoint, int reachedR, double endDiffSq, double meanRayDiff) {
            this.endpoint = endpoint;
            this.reachedR = reachedR;
            this.endDiffSq = endDiffSq;
            this.meanRayDiff = meanRayDiff;
        }
    }

    private static class JoinResult {
        final Point point;
        final double dist;
        JoinResult(Point p, double d) {
            this.point = p;
            this.dist = d;
        }
    }

    private static class TracingContext {
        Point current;
        double smoothedAngle;
        double lastScanAngle;
        double lastStepAngle;
        double estimatedHalfWidth = -1.0;
        int adaptiveTargetColor;
        double targetR;
        double targetG;
        double targetB;
        boolean searching = true;
        final int initialColor;

        TracingContext(Point start, double startAngle, int startColor) {
            this.current = start;
            this.smoothedAngle = startAngle;
            this.lastScanAngle = startAngle;
            this.lastStepAngle = startAngle;
            this.initialColor = startColor;
            this.adaptiveTargetColor = startColor;
            this.targetR = (startColor >> 16) & 0xFF;
            this.targetG = (startColor >> 8) & 0xFF;
            this.targetB = startColor & 0xFF;
        }

        void adaptColor(int newColor, double ratio) {
            int nr = (newColor >> 16) & 0xFF;
            int ng = (newColor >> 8) & 0xFF;
            int nb = newColor & 0xFF;

            int ir = (initialColor >> 16) & 0xFF;
            int ig = (initialColor >> 8) & 0xFF;
            int ib = initialColor & 0xFF;

            double anchoredR = 0.9 * nr + 0.1 * ir;
            double anchoredG = 0.9 * ng + 0.1 * ig;
            double anchoredB = 0.9 * nb + 0.1 * ib;

            this.targetR = this.targetR * (1.0 - ratio) + anchoredR * ratio;
            this.targetG = this.targetG * (1.0 - ratio) + anchoredG * ratio;
            this.targetB = this.targetB * (1.0 - ratio) + anchoredB * ratio;

            int r = Math.max(0, Math.min(255, (int) Math.round(this.targetR)));
            int g = Math.max(0, Math.min(255, (int) Math.round(this.targetG)));
            int b = Math.max(0, Math.min(255, (int) Math.round(this.targetB)));

            this.adaptiveTargetColor = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
