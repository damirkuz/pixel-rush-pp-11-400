package com.team.imgproc.engine;

import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ForkJoinEngine implements ProcessingEngine {
    private static final int SEQUENTIAL_THRESHOLD = 64;

    private final FilterRegistry filters;

    public ForkJoinEngine(FilterRegistry filters) {
        this.filters = Objects.requireNonNull(filters, "filters");
    }

    @Override
    public String mode() {
        return "forkjoin";
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

        var pool = new ForkJoinPool(threads);
        return ParallelBatch.process(pool, batch, (image, cancelled) -> processImage(image, steps, cancelled));
    }

    private static ImageData processImage(ImageData image, List<ResolvedStep> steps, AtomicBoolean cancelled) {
        int width = image.width();
        int height = image.height();
        ImageData current = image;
        for (ResolvedStep step : steps) {
            ParallelBatch.checkCancelled(cancelled);
            int[] destination = new int[width * height];
            try {
                new RowRangeTask(current, destination, 0, height, step.filter(), step.params(), cancelled).invoke();
            } catch (RegionFailure e) {
                throw ParallelBatch.processingFailure(e.getCause());
            }
            ParallelBatch.checkCancelled(cancelled);
            current = new ImageData(image.name(), width, height, destination);
        }
        return current;
    }

    private record ResolvedStep(ImageFilter filter, Map<String, Double> params) {}

    // ForkJoinTask can recreate exceptions with public constructors when a different thread joins.
    private static final class RegionFailure extends RuntimeException {
        RegionFailure(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    private static final class RowRangeTask extends RecursiveAction {
        private final ImageData src;
        private final int[] dst;
        private final int startY;
        private final int endY;
        private final ImageFilter filter;
        private final Map<String, Double> params;
        private final AtomicBoolean cancelled;

        private RowRangeTask(ImageData src, int[] dst, int startY, int endY,
                             ImageFilter filter, Map<String, Double> params, AtomicBoolean cancelled) {
            this.src = src;
            this.dst = dst;
            this.startY = startY;
            this.endY = endY;
            this.filter = filter;
            this.params = params;
            this.cancelled = cancelled;
        }

        @Override
        protected void compute() {
            ParallelBatch.checkCancelled(cancelled);
            if (endY - startY <= SEQUENTIAL_THRESHOLD) {
                try {
                    filter.applyRegion(src, dst, startY, endY, params);
                } catch (RuntimeException | Error e) {
                    throw new RegionFailure(e);
                }
                return;
            }
            int mid = startY + (endY - startY) / 2;
            invokeAll(
                    new RowRangeTask(src, dst, startY, mid, filter, params, cancelled),
                    new RowRangeTask(src, dst, mid, endY, filter, params, cancelled)
            );
        }
    }
}
