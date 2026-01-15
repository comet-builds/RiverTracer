package org.openstreetmap.josm.plugins.rivertracer;

import java.awt.Point;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Core engine for tracing rivers from imagery based on color similarity and geometry.
 */
public class RiverTracingEngine {

    private static final int EDGE_MARGIN = 10;
    private static final double SCAN_ARC_STANDARD = Math.PI / 3.0;
    private static final double SCAN_ARC_SHARP = Math.PI / 1.5;
    private static final double SCAN_ARC_GAP = Math.PI / 4.0;
    private static final double ANGLE_SMOOTHING_FACTOR = 0.3;
    private static final double COLOR_ADAPTATION_RATE = 0.05;

    private final RiverTracingOptions options;

    public RiverTracingEngine(RiverTracingOptions options) {
        this.options = options;
    }

    /**
     * Traces a river starting from a specific point in both directions.
     *
     * @param snapshot The imagery snapshot.
     * @param start The starting point on the image.
     * @param existingWaterways List of existing waterway segments for connection detection.
     * @return A list of points representing the traced path.
     */
    public List<Point> trace(BufferedImage snapshot, Point start, List<Line2D> existingWaterways) {
        int startColor = snapshot.getRGB(start.x, start.y);
        double primaryAngle = findInitialDirection(snapshot, start, startColor);

        List<Point> forwardPath = findPath(snapshot, start, primaryAngle, existingWaterways);
        List<Point> backwardPath = findPath(snapshot, start, primaryAngle + Math.PI, existingWaterways);

        List<Point> fullPath = new ArrayList<>(backwardPath);
        Collections.reverse(fullPath);

        if (!forwardPath.isEmpty()) {
            forwardPath.remove(0);
            fullPath.addAll(forwardPath);
        }

        if (options.getSmoothness() > 0) {
            smoothPath(fullPath, options.getSmoothness());
        }

        return fullPath;
    }

    private List<Point> findPath(BufferedImage img, Point start, double startAngle, List<Line2D> waterways) {
        List<Point> path = new ArrayList<>();
        path.add(start);

        int width = img.getWidth();
        int height = img.getHeight();

        if (!isValidPoint(start, width, height)) return path;

        int initialColor = img.getRGB(start.x, start.y);
        int adaptiveTargetColor = initialColor;
        Point current = start;
        double smoothedAngle = startAngle;
        double joinDistance = 1.5 * options.getStepSize();

        boolean searching = true;
        while (searching) {
            if (isAtEdge(current, width, height)) {
                searching = false;
                continue;
            }

            ScanResult res = performMultiStageScan(img, current, smoothedAngle, adaptiveTargetColor);
            
            if (res == null) {
                searching = false;
                continue;
            }

            Point centeredNext = adjustToCenter(img, res.point, res.angle, adaptiveTargetColor, width, height);

            if (shouldStopAtAngle(path, current, centeredNext) || intersectsSelf(path, centeredNext)) {
                searching = false;
                continue;
            }

            JoinResult join = checkJoin(centeredNext, waterways, joinDistance);
            if (join != null) {
                path.add(join.point);
                searching = false;
                continue;
            }

            path.add(centeredNext);

            double actualAngle = Math.atan2(centeredNext.y - current.y, centeredNext.x - current.x);
            smoothedAngle = interpolateAngle(smoothedAngle, actualAngle, ANGLE_SMOOTHING_FACTOR);

            current = centeredNext;
            int newColor = img.getRGB(current.x, current.y);

            if (colorDistance(initialColor, newColor) > options.getMaxJump()) {
                searching = false;
            } else {
                adaptiveTargetColor = blendColors(adaptiveTargetColor, newColor, COLOR_ADAPTATION_RATE);
            }
        }

        return path;
    }

    private ScanResult performMultiStageScan(BufferedImage img, Point current, double angle, int targetColor) {
        int step = options.getStepSize();
        double tol = options.getColorTolerance();

        ScanResult res = scanSurroundings(img, current, angle, step, SCAN_ARC_STANDARD, targetColor, tol);

        if (res == null) {
            res = scanSurroundings(img, current, angle, step, SCAN_ARC_SHARP, targetColor, tol * 1.3);
        }

        if (res == null) {
            res = scanSurroundings(img, current, angle, (int) (step * 2.5), SCAN_ARC_GAP, targetColor, tol * 1.5);
        }
        return res;
    }

    private boolean shouldStopAtAngle(List<Point> path, Point current, Point next) {
        if (path.size() < 2) return false;
        Point prev = path.get(path.size() - 2);
        
        double angle1 = Math.atan2(current.y - prev.y, current.x - prev.x);
        double angle2 = Math.atan2(next.y - current.y, next.x - current.x);
        
        double diff = angle2 - angle1;
        while (diff <= -Math.PI) diff += 2 * Math.PI;
        while (diff > Math.PI) diff -= 2 * Math.PI;

        return Math.abs(Math.toDegrees(diff)) >= options.getMaxAngle();
    }

    private void smoothPath(List<Point> path, int iterations) {
        if (path.size() < 3) return;

        List<Point2D.Double> doublePath = new ArrayList<>(path.size());
        for (Point p : path) {
            doublePath.add(new Point2D.Double(p.x, p.y));
        }

        for (int iter = 0; iter < iterations; iter++) {
            List<Point2D.Double> nextPath = new ArrayList<>(doublePath);

            for (int i = 1; i < doublePath.size() - 1; i++) {
                Point2D.Double pPrev = doublePath.get(i - 1);
                Point2D.Double pCurr = doublePath.get(i);
                Point2D.Double pNext = doublePath.get(i + 1);

                double newX = (pPrev.x + 2.0 * pCurr.x + pNext.x) / 4.0;
                double newY = (pPrev.y + 2.0 * pCurr.y + pNext.y) / 4.0;

                nextPath.set(i, new Point2D.Double(newX, newY));
            }
            doublePath = nextPath;
        }

        for (int i = 0; i < path.size(); i++) {
            Point2D.Double p = doublePath.get(i);
            path.set(i, new Point((int) Math.round(p.x), (int) Math.round(p.y)));
        }
    }

    private double findInitialDirection(BufferedImage img, Point p, int target) {
        double bestAngle = 0;
        double minDiff = Double.MAX_VALUE;
        int stepSize = options.getStepSize();

        for (double angle = 0; angle < 2 * Math.PI; angle += Math.PI / 16) {
            int nx = (int) (p.x + Math.cos(angle) * stepSize);
            int ny = (int) (p.y + Math.sin(angle) * stepSize);

            if (!isValidPoint(new Point(nx, ny), img.getWidth(), img.getHeight())) continue;

            double diff = colorDistance(target, img.getRGB(nx, ny));
            if (diff < minDiff) {
                minDiff = diff;
                bestAngle = angle;
            }
        }
        return bestAngle;
    }

    private JoinResult checkJoin(Point p, List<Line2D> waterways, double threshold) {
        JoinResult best = null;
        double minD = Double.MAX_VALUE;
        Point2D.Double p2d = new Point2D.Double(p.x, p.y);

        for (Line2D l : waterways) {
            double dist = l.ptSegDist(p2d);
            if (dist < threshold && dist < minD) {
                minD = dist;
                Point proj = getProjectedPoint(l, p);
                best = new JoinResult(proj, dist);
            }
        }
        return best;
    }

    private Point getProjectedPoint(Line2D l, Point p) {
        double x1 = l.getX1();
        double y1 = l.getY1();
        double x2 = l.getX2();
        double y2 = l.getY2();
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0 && dy == 0) return new Point((int)x1, (int)y1);

        double t = ((p.x - x1) * dx + (p.y - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));

        return new Point((int)(x1 + t * dx), (int)(y1 + t * dy));
    }

    private boolean intersectsSelf(List<Point> path, Point next) {
        if (path.isEmpty()) return false;
        Point current = path.get(path.size() - 1);

        for (int i = 0; i < path.size() - 2; i++) {
            Point p1 = path.get(i);
            Point p2 = path.get(i + 1);

            if (Line2D.linesIntersect(p1.x, p1.y, p2.x, p2.y, current.x, current.y, next.x, next.y)) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidPoint(Point p, int w, int h) {
        return p.x >= 0 && p.y >= 0 && p.x < w && p.y < h;
    }

    private boolean isAtEdge(Point p, int w, int h) {
        return p.x <= EDGE_MARGIN || p.y <= EDGE_MARGIN || p.x >= w - EDGE_MARGIN || p.y >= h - EDGE_MARGIN;
    }

    private ScanResult scanSurroundings(BufferedImage img, Point center, double baseAngle, int radius, double scanArcRad, int targetColor, double tolerance) {
        Point bestPoint = null;
        double minScore = Double.MAX_VALUE;
        double bestAngleFound = baseAngle;
        int w = img.getWidth();
        int h = img.getHeight();

        for (double angle = baseAngle - scanArcRad; angle <= baseAngle + scanArcRad; angle += Math.PI / 32) {
            int nx = (int) (center.x + Math.cos(angle) * radius);
            int ny = (int) (center.y + Math.sin(angle) * radius);

            if (!isValidPoint(new Point(nx, ny), w, h)) continue;

            int c = img.getRGB(nx, ny);
            double diff = colorDistance(targetColor, c);
            double anglePenalty = Math.abs(angle - baseAngle) * 10.0;

            if ((diff + anglePenalty) < minScore && diff < tolerance) {
                minScore = diff + anglePenalty;
                bestPoint = new Point(nx, ny);
                bestAngleFound = angle;
            }
        }

        return bestPoint != null ? new ScanResult(bestPoint, bestAngleFound) : null;
    }

    private Point adjustToCenter(BufferedImage img, Point p, double angle, int target, int w, int h) {
        double perpAngle = angle + Math.PI / 2;
        int scanDist = 100;
        int leftLimit = 0;
        int rightLimit = 0;

        for (int r = 0; r < scanDist; r++) {
            int nx = (int) (p.x + Math.cos(perpAngle) * r);
            int ny = (int) (p.y + Math.sin(perpAngle) * r);
            if (!isColorMatch(img, nx, ny, target, w, h)) {
                leftLimit = r;
                break;
            }
            leftLimit = r;
        }

        for (int r = 0; r < scanDist; r++) {
            int nx = (int) (p.x - Math.cos(perpAngle) * r);
            int ny = (int) (p.y - Math.sin(perpAngle) * r);
            if (!isColorMatch(img, nx, ny, target, w, h)) {
                rightLimit = r;
                break;
            }
            rightLimit = r;
        }

        if (leftLimit == scanDist && rightLimit == scanDist) return p;

        int shift = (leftLimit - rightLimit) / 2;
        int finalX = (int) (p.x + Math.cos(perpAngle) * shift);
        int finalY = (int) (p.y + Math.sin(perpAngle) * shift);

        return new Point(
            Math.max(0, Math.min(w - 1, finalX)),
            Math.max(0, Math.min(h - 1, finalY))
        );
    }

    private boolean isColorMatch(BufferedImage img, int x, int y, int target, int w, int h) {
        if (!isValidPoint(new Point(x, y), w, h)) return false;
        return colorDistance(target, img.getRGB(x, y)) < (options.getColorTolerance() * 1.1);
    }

    private double interpolateAngle(double oldAngle, double newAngle, double factor) {
        double diff = newAngle - oldAngle;
        while (diff <= -Math.PI) diff += 2 * Math.PI;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        return oldAngle + diff * factor;
    }

    private static double colorDistance(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF;
        int g1 = (rgb1 >> 8) & 0xFF;
        int b1 = rgb1 & 0xFF;

        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;

        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;

        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int blendColors(int rgb1, int rgb2, double ratio) {
        int r1 = (rgb1 >> 16) & 0xFF;
        int g1 = (rgb1 >> 8) & 0xFF;
        int b1 = rgb1 & 0xFF;

        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;

        int r = (int) (r1 * (1 - ratio) + r2 * ratio);
        int g = (int) (g1 * (1 - ratio) + g2 * ratio);
        int b = (int) (b1 * (1 - ratio) + b2 * ratio);

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static class ScanResult {
        final Point point;
        final double angle;
        ScanResult(Point p, double a) {
            this.point = p;
            this.angle = a;
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
}
