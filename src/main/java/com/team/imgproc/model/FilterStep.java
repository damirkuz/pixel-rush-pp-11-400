package com.team.imgproc.model;

import java.util.Map;
import java.util.Objects;

public record FilterStep(String filterName, Map<String, Double> params) {
    public FilterStep {
        Objects.requireNonNull(filterName, "filterName");
        if (filterName.isBlank()) {
            throw new IllegalArgumentException("Filter name must be non-blank");
        }
        params = Map.copyOf(params);
    }
}
