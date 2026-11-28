package com.team.imgproc.engine;

import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;

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

        int parallelism = threads;
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        ImageData[] results = new ImageData[batch.size()];
        List<ForkJoinTask<Void>> tasks = new ArrayList<>(batch.size());

        try {
            for (int i = 0; i < batch.size(); i++) {
                final int index = i;
                final ImageData image = batch.get(i);
                tasks.add(pool.submit(() -> {
                    results[index] = processImage(image, steps);
                    return null;
                }));
            }

            for (ForkJoinTask<Void> task : tasks) {
                task.join();
            }

            return List.of(results);
        } catch (RuntimeException e) {
            cancelAll(tasks);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Batch processing failed", cause != null ? cause : e);
        } catch (Error e) {
            cancelAll(tasks);
            throw e;
        } finally {
            shutdownPool(pool);
        }
    }

    private static ImageData processImage(ImageData image, List<ResolvedStep> steps) {
        int width = image.width();
        int height = image.height();
        ImageData current = image;
        for (ResolvedStep step : steps) {
            int[] destination = new int[width * height];
            new RowRangeTask(current, destination, 0, height, step.filter(), step.params()).invoke();
            current = new ImageData(image.name(), width, height, destination);
        }
        return current;
    }

    private static void cancelAll(List<ForkJoinTask<Void>> tasks) {
        for (ForkJoinTask<Void> task : tasks) {
            task.cancel(true);
        }
    }

    private static void shutdownPool(ForkJoinPool pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ResolvedStep(ImageFilter filter, Map<String, Double> params) {}

    private static final class RowRangeTask extends RecursiveAction {
        private final ImageData src;
        private final int[] dst;
        private final int startY;
        private final int endY;
        private final ImageFilter filter;
        private final Map<String, Double> params;

        private RowRangeTask(ImageData src, int[] dst, int startY, int endY,
                             ImageFilter filter, Map<String, Double> params) {
            this.src = src;
            this.dst = dst;
            this.startY = startY;
            this.endY = endY;
            this.filter = filter;
            this.params = params;
        }

        @Override
        protected void compute() {
            if (endY - startY <= SEQUENTIAL_THRESHOLD) {
                filter.applyRegion(src, dst, startY, endY, params);
                return;
            }
            int mid = startY + (endY - startY) / 2;
            invokeAll(
                    new RowRangeTask(src, dst, startY, mid, filter, params),
                    new RowRangeTask(src, dst, mid, endY, filter, params)
            );
        }
    }
}