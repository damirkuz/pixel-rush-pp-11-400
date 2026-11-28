package com.team.imgproc.pipeline;

import com.team.imgproc.model.FilterStep;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PipelineSpec {
    private PipelineSpec() {}

    public static List<FilterStep> parse(String specification) {
        return Arrays.stream(specification.split(",", -1)).map(PipelineSpec::parseStep).toList();
    }

    private static FilterStep parseStep(String specification) {
        String[] fields = specification.split(":", -1);
        String name = fields[0].strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Each pipeline step must name a filter");
        }

        Map<String, Double> params = new HashMap<>();
        for (int i = 1; i < fields.length; i++) {
            String[] entry = fields[i].split("=", 2);
            String key = entry[0].strip();
            if (entry.length != 2 || key.isEmpty()) {
                throw new IllegalArgumentException("Expected key=value in filter " + name + ": " + fields[i]);
            }
            double value = Double.parseDouble(entry[1].strip());
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Parameter " + name + "." + key + " must be finite");
            }
            if (params.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate parameter: " + name + "." + key);
            }
        }
        return new FilterStep(name, params);
    }
}
