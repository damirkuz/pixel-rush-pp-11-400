package com.team.imgproc.engine;

import com.team.imgproc.model.ImageData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

final class ParallelBatch {
    private ParallelBatch() {}

    static List<ImageData> process(ExecutorService pool, List<ImageData> batch,
                                   BiFunction<ImageData, AtomicBoolean, ImageData> processImage) {
        var cancelled = new AtomicBoolean();
        // Use FutureTask even for a ForkJoinPool so get() carries the original failure.
        var completions = new ExecutorCompletionService<IndexedImage>(pool::execute);
        List<Future<IndexedImage>> futures = new ArrayList<>(batch.size());
        var results = new ImageData[batch.size()];
        boolean completed = false;
        try {
            for (int index = 0; index < batch.size(); index++) {
                int resultIndex = index;
                ImageData image = batch.get(index);
                futures.add(completions.submit(
                        () -> new IndexedImage(resultIndex, processImage.apply(image, cancelled))));
            }
            for (int index = 0; index < results.length; index++) {
                IndexedImage item = completions.take().get();
                results[item.index()] = item.image();
            }
            completed = true;
            return List.of(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Batch processing was interrupted", e);
        } catch (ExecutionException e) {
            throw processingFailure(e.getCause());
        } finally {
            if (completed) {
                pool.shutdown();
            } else {
                cancelled.set(true);
                for (Future<IndexedImage> future : futures) {
                    future.cancel(true);
                }
                pool.shutdownNow();
            }
        }
    }

    static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Batch processing was cancelled");
        }
    }

    static RuntimeException processingFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Batch processing failed", cause);
    }

    private record IndexedImage(int index, ImageData image) {}
}
