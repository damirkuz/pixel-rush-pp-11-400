package com.team.imgproc.pipeline;

import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ImagePipeline {
    private final Map<String, ProcessingEngine> engines;
    private final FilterRegistry filters;

    public ImagePipeline(List<ProcessingEngine> engines, FilterRegistry filters) {
        this.filters = filters;
        this.engines = engines.stream()
                .collect(Collectors.toUnmodifiableMap(ProcessingEngine::mode, Function.identity()));
    }

    public List<ImageData> process(List<ImageData> images, List<FilterStep> steps, String mode, int threads) {
        ProcessingEngine engine = engines.get(mode);
        if (engine == null) {
            throw new IllegalArgumentException("Unknown processing mode: " + mode);
        }
        for (FilterStep step : steps) {
            var accepted = filters.require(step.filterName()).parameterNames();
            for (String parameter : step.params().keySet()) {
                if (!accepted.contains(parameter)) {
                    throw new IllegalArgumentException("Unknown parameter '" + parameter
                            + "' for filter " + step.filterName());
                }
            }
        }
        return engine.processBatch(images, steps, threads);
    }
}
