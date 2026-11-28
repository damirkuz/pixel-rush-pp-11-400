package com.team.imgproc.engine;

import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ThreadPoolEngine implements ProcessingEngine {
    private final FilterRegistry filters;

    public ThreadPoolEngine(FilterRegistry filters) {
        this.filters = Objects.requireNonNull(filters, "filters");
    }

    @Override
    public String mode() {
        return "threadpool";
    }

    @Override
    public List<ImageData> processBatch(List<ImageData> images, List<FilterStep> pipeline, int threads) {
        Objects.requireNonNull(images, "images");
        Objects.requireNonNull(pipeline, "pipeline");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }

        List<ImageData> batch = List.copyOf(images);
        List<ResolvedStep> steps = pipeline.stream()
                .map(step -> {
                    Objects.requireNonNull(step, "step");
                    return new ResolvedStep(filters.require(step.filterName()), step.params());
                })
                .toList();

        if (steps.isEmpty() || batch.isEmpty()) {
            return batch;
        }

        int workerCount = Math.min(threads, batch.size());
        AtomicInteger workerId = new AtomicInteger(1);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "pixelrush-worker-" + workerId.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        });

        return ParallelBatch.process(pool, batch, (image, cancelled) -> processImage(image, steps, cancelled));
    }

    private static ImageData processImage(ImageData image, List<ResolvedStep> steps, AtomicBoolean cancelled) {
        int width = image.width();
        int height = image.height();
        ImageData current = image;
        for (ResolvedStep step : steps) {
            ParallelBatch.checkCancelled(cancelled);
            int[] destination = new int[width * height];
            step.filter().applyRegion(current, destination, 0, height, step.params());
            ParallelBatch.checkCancelled(cancelled);
            current = new ImageData(image.name(), width, height, destination);
        }
        return current;
    }

    private record ResolvedStep(ImageFilter filter, Map<String, Double> params) {}
}
