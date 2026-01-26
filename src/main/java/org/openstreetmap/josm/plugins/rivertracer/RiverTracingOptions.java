package org.openstreetmap.josm.plugins.rivertracer;

/**
 * Data class holding configuration parameters for the river tracing algorithm.
 */
public class RiverTracingOptions {
    public static final int MIN_STEP_SIZE = 10;
    public static final int MAX_STEP_SIZE = 60;
    public static final int DEFAULT_STEP_SIZE = 15;

    public static final double MIN_COLOR_TOLERANCE = 10.0;
    public static final double MAX_COLOR_TOLERANCE = 130.0;
    public static final double DEFAULT_COLOR_TOLERANCE = 55.0;

    public static final double MIN_MAX_JUMP = 15.0;
    public static final double MAX_MAX_JUMP = 200.0;
    public static final double DEFAULT_MAX_JUMP = 95.0;

    public static final double MIN_MAX_ANGLE = 30.0;
    public static final double MAX_MAX_ANGLE = 170.0;
    public static final double DEFAULT_MAX_ANGLE = 140.0;

    public static final int MIN_SMOOTHNESS = 0;
    public static final int MAX_SMOOTHNESS = 10;
    public static final int DEFAULT_SMOOTHNESS = 1;

    public static final boolean DEFAULT_IS_RIVER = true;
    public static final boolean DEFAULT_IS_INTERMITTENT = false;

    private int stepSize = DEFAULT_STEP_SIZE;
    private double colorTolerance = DEFAULT_COLOR_TOLERANCE;
    private double maxJump = DEFAULT_MAX_JUMP;
    private double maxAngle = DEFAULT_MAX_ANGLE;
    private int smoothness = DEFAULT_SMOOTHNESS;

    private boolean river = DEFAULT_IS_RIVER; // true for river, false for stream
    private boolean intermittent = DEFAULT_IS_INTERMITTENT;

    public int getStepSize() { return stepSize; }
    public void setStepSize(int stepSize) { this.stepSize = stepSize; }

    public double getColorTolerance() { return colorTolerance; }
    public void setColorTolerance(double colorTolerance) { this.colorTolerance = colorTolerance; }

    public double getMaxJump() { return maxJump; }
    public void setMaxJump(double maxJump) { this.maxJump = maxJump; }

    public double getMaxAngle() { return maxAngle; }
    public void setMaxAngle(double maxAngle) { this.maxAngle = maxAngle; }

    public int getSmoothness() { return smoothness; }
    public void setSmoothness(int smoothness) { this.smoothness = smoothness; }

    public boolean isRiver() { return river; }
    public void setRiver(boolean river) { this.river = river; }

    public boolean isIntermittent() { return intermittent; }
    public void setIntermittent(boolean intermittent) { this.intermittent = intermittent; }
}
