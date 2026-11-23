package com.team.imgproc.demo;

import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.engine.SequentialEngine;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.GaussianBlurFilter;
import com.team.imgproc.io.ImageFileIO;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Standalone Gaussian demonstration for make run-seq. */
public final class SequentialDemo {
    private SequentialDemo() {}

    public static void main(String[] args) throws IOException {
        Path input = requiredPath("PIXELRUSH_INPUT");
        Path output = requiredPath("PIXELRUSH_OUTPUT");
        Map<String, Double> params = new HashMap<>();
        addParameter(params, "radius", "PIXELRUSH_RADIUS");
        addParameter(params, "sigma", "PIXELRUSH_SIGMA");

        var engine = new SequentialEngine(new FilterRegistry(List.of(new GaussianBlurFilter())));
        int count = run(input, output, params, new ImageFileIO(), engine);
        System.out.printf("Processed %d image(s) into %s%n", count, output.toAbsolutePath());
    }

    static int run(Path input, Path output, Map<String, Double> params, ImageFileIO io, ProcessingEngine engine)
            throws IOException {
        if (Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IOException("Input and output directories must be different: " + input);
        }
        List<ImageData> images = io.readDirectory(input);
        List<ImageData> results = engine.processBatch(images, List.of(new FilterStep("gaussian", params)), 1);
        io.writeDirectory(output, results);
        return results.size();
    }

    private static Path requiredPath(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + key + " to a directory path");
        }
        return Path.of(value);
    }

    private static void addParameter(Map<String, Double> params, String name, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            params.put(name, Double.valueOf(value));
        }
    }
}
