package com.team.imgproc.engine;

import com.team.imgproc.filter.ColumnTiledFilter;
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
import java.util.function.BiConsumer;

@Component
public class ForkJoinEngine implements ProcessingEngine {
    private static final int MIN_REGION_PIXELS = 8_192;

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
        int regionsPerImage = Math.ceilDiv(2 * threads, batch.size());
        return ParallelBatch.process(pool, batch,
                (image, cancelled) -> processImage(image, steps, regionsPerImage, cancelled));
    }

    private static ImageData processImage(ImageData image, List<ResolvedStep> steps, int regionsPerImage,
                                          AtomicBoolean cancelled) {
        int width = image.width();
        int height = image.height();
        ImageData current = image;
        for (ResolvedStep step : steps) {
            ParallelBatch.checkCancelled(cancelled);
            int[] destination = new int[width * height];
            try {
                applyStep(current, destination, step, regionsPerImage, cancelled);
            } catch (RegionFailure e) {
                throw ParallelBatch.processingFailure(e.getCause());
            }
            ParallelBatch.checkCancelled(cancelled);
            current = new ImageData(image.name(), width, height, destination);
        }
        return current;
    }

    private static void applyStep(ImageData src, int[] destination, ResolvedStep step,
                                  int regionsPerImage, AtomicBoolean cancelled) {
        if (step.filter() instanceof ColumnTiledFilter tiled) {
            var operation = tiled.prepareColumns(src, destination, step.params(),
                    () -> ParallelBatch.checkCancelled(cancelled));
            int maxColumns = regionLength(src.width(), src.height(), regionsPerImage);
            new RangeTask(0, src.width(), maxColumns, operation::apply, cancelled).invoke();
        } else {
            int maxRows = regionLength(src.height(), src.width(), regionsPerImage);
            new RangeTask(0, src.height(), maxRows,
                    (start, end) -> step.filter().applyRegion(src, destination, start, end, step.params()),
                    cancelled).invoke();
        }
    }

    private static int regionLength(int extent, int otherExtent, int regionsPerImage) {
        return Math.max(Math.ceilDiv(extent, regionsPerImage), Math.ceilDiv(MIN_REGION_PIXELS, otherExtent));
    }

    private record ResolvedStep(ImageFilter filter, Map<String, Double> params) {}

    // ForkJoinTask can recreate exceptions with public constructors when a different thread joins.
    private static final class RegionFailure extends RuntimeException {
        RegionFailure(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    private static final class RangeTask extends RecursiveAction {
        private final int start;
        private final int end;
        private final int maxLength;
        private final BiConsumer<Integer, Integer> apply;
        private final AtomicBoolean cancelled;

        private RangeTask(int start, int end, int maxLength,
                          BiConsumer<Integer, Integer> apply, AtomicBoolean cancelled) {
            this.start = start;
            this.end = end;
            this.maxLength = maxLength;
            this.apply = apply;
            this.cancelled = cancelled;
        }

        @Override
        protected void compute() {
            ParallelBatch.checkCancelled(cancelled);
            if (end - start <= maxLength) {
                try {
                    apply.accept(start, end);
                } catch (RuntimeException | Error e) {
                    throw new RegionFailure(e);
                }
                return;
            }
            int mid = start + (end - start) / 2;
            invokeAll(
                    new RangeTask(start, mid, maxLength, apply, cancelled),
                    new RangeTask(mid, end, maxLength, apply, cancelled)
            );
        }
    }
}
