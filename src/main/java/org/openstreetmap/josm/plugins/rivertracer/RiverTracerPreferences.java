package org.openstreetmap.josm.plugins.rivertracer;

import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Handles persistence of RiverTracer options using JOSM's global configuration.
 */
public class RiverTracerPreferences {

    private static final String PREF_KEY_STEP_SIZE = "rivertracer.step_size";
    private static final String PREF_KEY_COLOR_TOLERANCE = "rivertracer.color_tolerance";
    private static final String PREF_KEY_MAX_JUMP = "rivertracer.max_jump";
    private static final String PREF_KEY_MAX_ANGLE = "rivertracer.max_angle";
    private static final String PREF_KEY_SMOOTHNESS = "rivertracer.smoothness";
    private static final String PREF_KEY_IS_RIVER = "rivertracer.is_river";
    private static final String PREF_KEY_INTERMITTENT = "rivertracer.intermittent";

    public RiverTracingOptions getOptions() {
        RiverTracingOptions options = new RiverTracingOptions();

        int stepSize = Config.getPref().getInt(PREF_KEY_STEP_SIZE, RiverTracingOptions.DEFAULT_STEP_SIZE);
        options.setStepSize(clamp(stepSize, RiverTracingOptions.MIN_STEP_SIZE, RiverTracingOptions.MAX_STEP_SIZE));

        double tolerance = Config.getPref().getDouble(PREF_KEY_COLOR_TOLERANCE, RiverTracingOptions.DEFAULT_COLOR_TOLERANCE);
        options.setColorTolerance(clamp(tolerance, RiverTracingOptions.MIN_COLOR_TOLERANCE, RiverTracingOptions.MAX_COLOR_TOLERANCE));

        double jump = Config.getPref().getDouble(PREF_KEY_MAX_JUMP, RiverTracingOptions.DEFAULT_MAX_JUMP);
        options.setMaxJump(clamp(jump, RiverTracingOptions.MIN_MAX_JUMP, RiverTracingOptions.MAX_MAX_JUMP));

        double angle = Config.getPref().getDouble(PREF_KEY_MAX_ANGLE, RiverTracingOptions.DEFAULT_MAX_ANGLE);
        options.setMaxAngle(clamp(angle, RiverTracingOptions.MIN_MAX_ANGLE, RiverTracingOptions.MAX_MAX_ANGLE));

        int smoothness = Config.getPref().getInt(PREF_KEY_SMOOTHNESS, RiverTracingOptions.DEFAULT_SMOOTHNESS);
        options.setSmoothness(clamp(smoothness, RiverTracingOptions.MIN_SMOOTHNESS, RiverTracingOptions.MAX_SMOOTHNESS));

        options.setRiver(Config.getPref().getBoolean(PREF_KEY_IS_RIVER, RiverTracingOptions.DEFAULT_IS_RIVER));
        options.setIntermittent(Config.getPref().getBoolean(PREF_KEY_INTERMITTENT, RiverTracingOptions.DEFAULT_IS_INTERMITTENT));
        return options;
    }

    public void saveOptions(RiverTracingOptions options) {
        Config.getPref().putInt(PREF_KEY_STEP_SIZE, options.getStepSize());
        Config.getPref().putDouble(PREF_KEY_COLOR_TOLERANCE, options.getColorTolerance());
        Config.getPref().putDouble(PREF_KEY_MAX_JUMP, options.getMaxJump());
        Config.getPref().putDouble(PREF_KEY_MAX_ANGLE, options.getMaxAngle());
        Config.getPref().putInt(PREF_KEY_SMOOTHNESS, options.getSmoothness());
        Config.getPref().putBoolean(PREF_KEY_IS_RIVER, options.isRiver());
        Config.getPref().putBoolean(PREF_KEY_INTERMITTENT, options.isIntermittent());
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
