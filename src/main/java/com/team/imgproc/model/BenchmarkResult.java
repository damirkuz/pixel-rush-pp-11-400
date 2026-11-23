package com.team.imgproc.model;

import java.util.Objects;

public record BenchmarkResult(
        String engineMode,
        String filterName,
        int imageCount,
        int width,
        int height,
        int threads,
        double computeMs,
        double totalMs,
        double speedup,
        double efficiency,
        double totalSpeedup,
        double totalEfficiency,
        boolean correct,
        int maxDiff,
        double mae
) {
    public BenchmarkResult {
        Objects.requireNonNull(engineMode, "engineMode");
        Objects.requireNonNull(filterName, "filterName");
        if (engineMode.isBlank() || filterName.isBlank()) {
            throw new IllegalArgumentException("engineMode and filterName must be non-blank");
        }
        if (imageCount < 0 || width <= 0 || height <= 0 || threads < 1) {
            throw new IllegalArgumentException("Expected valid batch dimensions and positive threads");
        }
        if (computeMs < 0 || totalMs < 0 || speedup < 0 || efficiency < 0
                || totalSpeedup < 0 || totalEfficiency < 0 || maxDiff < 0 || mae < 0) {
            throw new IllegalArgumentException("Metrics must be non-negative");
        }
    }
}
