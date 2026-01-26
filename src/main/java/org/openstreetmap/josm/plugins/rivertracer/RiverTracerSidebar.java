package org.openstreetmap.josm.plugins.rivertracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;

import org.openstreetmap.josm.gui.dialogs.ToggleDialog;

/**
 * Sidebar panel for configuring river tracing parameters.
 */
public class RiverTracerSidebar extends ToggleDialog {

    private final RiverTracerPreferences preferences;
    private JRadioButton riverBtn;
    private JRadioButton streamBtn;
    private JCheckBox intermittentCheck;

    public RiverTracerSidebar() {
        super(tr("RiverTracer"), "river_icon", tr("RiverTracer Settings"), null, 150);
        this.preferences = new RiverTracerPreferences();

        JPanel panel = new JPanel();
        buildContent(panel);
        createLayout(panel, false, null);
    }

    private void buildContent(JPanel panel) {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        RiverTracingOptions options = preferences.getOptions();

        addSlider(panel, tr("Step Size"), 
            RiverTracingOptions.MIN_STEP_SIZE, 
            RiverTracingOptions.MAX_STEP_SIZE, 
            options.getStepSize(),
            RiverTracingOptions.DEFAULT_STEP_SIZE,
            val -> {
                RiverTracingOptions opts = preferences.getOptions();
                opts.setStepSize(val);
                preferences.saveOptions(opts);
            });

        addSlider(panel, tr("Color Tolerance"), 
            (int)RiverTracingOptions.MIN_COLOR_TOLERANCE, 
            (int)RiverTracingOptions.MAX_COLOR_TOLERANCE, 
            (int)options.getColorTolerance(),
            (int)RiverTracingOptions.DEFAULT_COLOR_TOLERANCE,
            val -> {
                RiverTracingOptions opts = preferences.getOptions();
                opts.setColorTolerance(val);
                preferences.saveOptions(opts);
            });

        addSlider(panel, tr("Max Jump"), 
            (int)RiverTracingOptions.MIN_MAX_JUMP, 
            (int)RiverTracingOptions.MAX_MAX_JUMP, 
            (int)options.getMaxJump(),
            (int)RiverTracingOptions.DEFAULT_MAX_JUMP,
            val -> {
                RiverTracingOptions opts = preferences.getOptions();
                opts.setMaxJump(val);
                preferences.saveOptions(opts);
            });

        addSlider(panel, tr("Max Angle"), 
            (int)RiverTracingOptions.MIN_MAX_ANGLE, 
            (int)RiverTracingOptions.MAX_MAX_ANGLE, 
            (int)options.getMaxAngle(),
            (int)RiverTracingOptions.DEFAULT_MAX_ANGLE,
            val -> {
                RiverTracingOptions opts = preferences.getOptions();
                opts.setMaxAngle(val);
                preferences.saveOptions(opts);
            });

        addSlider(panel, tr("Smoothing"), 
            RiverTracingOptions.MIN_SMOOTHNESS, 
            RiverTracingOptions.MAX_SMOOTHNESS, 
            options.getSmoothness(),
            RiverTracingOptions.DEFAULT_SMOOTHNESS,
            val -> {
                RiverTracingOptions opts = preferences.getOptions();
                opts.setSmoothness(val);
                preferences.saveOptions(opts);
            });

        addTaggingControls(panel, options);
    }

    private void addTaggingControls(JPanel panel, RiverTracingOptions options) {
        JLabel typeLabel = new JLabel(tr("Waterway Type:"));
        typeLabel.setAlignmentX(0.0f);
        panel.add(typeLabel);

        riverBtn = new JRadioButton(tr("River"), options.isRiver());
        streamBtn = new JRadioButton(tr("Stream"), !options.isRiver());
        
        ButtonGroup bg = new ButtonGroup();
        bg.add(riverBtn);
        bg.add(streamBtn);

        JPanel typePanel = new JPanel();
        typePanel.setLayout(new BoxLayout(typePanel, BoxLayout.X_AXIS));
        typePanel.add(riverBtn);
        typePanel.add(streamBtn);
        typePanel.setAlignmentX(0.0f);
        panel.add(typePanel);

        riverBtn.addActionListener(e -> updateTags());
        streamBtn.addActionListener(e -> updateTags());

        intermittentCheck = new JCheckBox(tr("Intermittent"), options.isIntermittent());
        intermittentCheck.setAlignmentX(0.0f);
        panel.add(intermittentCheck);
        intermittentCheck.addActionListener(e -> updateTags());
    }

    private void updateTags() {
        RiverTracingOptions opts = preferences.getOptions();
        opts.setRiver(riverBtn.isSelected());
        opts.setIntermittent(intermittentCheck.isSelected());
        preferences.saveOptions(opts);
    }

    private void addSlider(JPanel parent, String label, int min, int max, int value, int def, IntConsumer onChange) {
        parent.add(createLabeledSlider(min, max, value, label, onChange, def));
        parent.add(Box.createRigidArea(new Dimension(0, 5)));
    }

    private JPanel createLabeledSlider(int min, int max, int value, String label, IntConsumer onChange, int defaultValue) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(0.0f);

        JLabel lbl = new JLabel(label + ": " + value);
        lbl.setAlignmentX(0.0f);
        p.add(lbl);

        JSlider slider = new JSlider(min, max, value);
        slider.setAlignmentX(0.0f);
        slider.setMajorTickSpacing((max - min) / 5);
        slider.setPaintTicks(true);

        slider.addChangeListener(e -> {
            lbl.setText(label + ": " + slider.getValue());
            if (!slider.getValueIsAdjusting()) {
                onChange.accept(slider.getValue());
            }
        });

        slider.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    slider.setValue(defaultValue);
                    onChange.accept(defaultValue);
                }
            }
        });

        p.add(slider);
        return p;
    }
}
