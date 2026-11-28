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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

        CompletionService<IndexedImage> completionService = new ExecutorCompletionService<>(pool);
        List<Future<IndexedImage>> futures = new ArrayList<>(batch.size());
        ImageData[] results = new ImageData[batch.size()];

        try {
            for (int i = 0; i < batch.size(); i++) {
                final int index = i;
                final ImageData image = batch.get(i);
                futures.add(completionService.submit(() -> new IndexedImage(index, processImage(image, steps))));
            }

            for (int completed = 0; completed < batch.size(); completed++) {
                IndexedImage item = completionService.take().get();
                results[item.index()] = item.image();
            }

            return List.of(results);
        } catch (InterruptedException e) {
            cancelAll(futures);
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Batch processing was interrupted", e);
        } catch (ExecutionException e) {
            cancelAll(futures);
            pool.shutdownNow();
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Batch processing failed", cause);
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
            step.filter().applyRegion(current, destination, 0, height, step.params());
            current = new ImageData(image.name(), width, height, destination);
        }
        return current;
    }

    private static void cancelAll(List<Future<IndexedImage>> futures) {
        for (Future<IndexedImage> future : futures) {
            future.cancel(true);
        }
    }

    private static void shutdownPool(ExecutorService pool) {
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

    private record IndexedImage(int index, ImageData image) {}
}
