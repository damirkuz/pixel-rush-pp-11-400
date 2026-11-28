package com.team.imgproc.engine;

import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SequentialEngine implements ProcessingEngine {
    private final FilterRegistry filters;

    public SequentialEngine(FilterRegistry filters) {
        this.filters = filters;
    }

    @Override
    public String mode() {
        return "sequential";
    }

    @Override
    public List<ImageData> processBatch(List<ImageData> images, List<FilterStep> pipeline, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }

        List<ResolvedStep> steps = pipeline.stream()
                .map(step -> new ResolvedStep(filters.require(step.filterName()), step.params()))
                .toList();
        if (steps.isEmpty()) {
            return List.copyOf(images);
        }

        List<ImageData> results = new ArrayList<>(images.size());
        for (ImageData image : images) {
            int width = image.width();
            int height = image.height();
            ImageData current = image;
            for (ResolvedStep step : steps) {
                int[] destination = new int[width * height];
                step.filter().applyRegion(current, destination, 0, height, step.params());
                current = new ImageData(image.name(), width, height, destination);
            }
            results.add(current);
        }

        return List.copyOf(results);
    }

    private record ResolvedStep(ImageFilter filter, Map<String, Double> params) {}
}
