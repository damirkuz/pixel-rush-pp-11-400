package com.team.imgproc.engine;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.filter.MedianFilter;
import com.team.imgproc.filter.SobelEdgeFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PixelRushApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ForkJoinEngineTest {
    @Autowired
    private List<ProcessingEngine> engines;

    @Test
    void registersForkJoinEngineInSpringContext() {
        ProcessingEngine forkJoin = engines.stream()
                .filter(engine -> engine.mode().equals("forkjoin"))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(ForkJoinEngine.class, forkJoin);
    }

    @Test
    void matchesSequentialAndThreadPoolOnSobelAndMedian() {
        var registry = new FilterRegistry(List.of(new SobelEdgeFilter(), new MedianFilter()));
        var forkJoin = new ForkJoinEngine(registry);
        var threadPool = new ThreadPoolEngine(registry);

        Random random = new Random(7);
        List<ImageData> batch = List.of(
                new ImageData("a.png", 67, 257, random.ints(67 * 257).toArray()),
                new ImageData("b.png", 20, 10, random.ints(20 * 10).toArray()),
                new ImageData("c.png", 8, 15, random.ints(8 * 15).toArray())
        );
        List<FilterStep> pipeline = List.of(
                new FilterStep("sobel", Map.of("scale", 1.1)),
                new FilterStep("median", Map.of("radius", 1.0))
        );

        List<ImageData> reference = new SequentialEngine(registry).processBatch(batch, pipeline, 1);
        for (int threads : new int[]{2, 4}) {
            assertEquals(reference, forkJoin.processBatch(batch, pipeline, threads),
                    "forkjoin threads=" + threads);
            assertEquals(reference, threadPool.processBatch(batch, pipeline, threads),
                    "threadpool threads=" + threads);
        }
    }

    @Test
    void preservesInputOrderEvenWhenLaterImagesFinishFirst() {
        CountDownLatch secondFinished = new CountDownLatch(1);
        ImageFilter orderedFilter = new ImageFilter() {
            @Override
            public String name() {
                return "ordered-test";
            }

            @Override
            public void applyRegion(ImageData src, int[] dst, int startY, int endY, Map<String, Double> params) {
                if (src.name().equals("slow.png")) {
                    try {
                        assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                int delta = params.get("delta").intValue();
                for (int i = startY * src.width(); i < endY * src.width(); i++) {
                    dst[i] = src.argbAt(i) + delta;
                }
                if (src.name().equals("fast.png")) {
                    secondFinished.countDown();
                }
            }
        };

        var engine = new ForkJoinEngine(new FilterRegistry(List.of(orderedFilter)));
        var slow = new ImageData("slow.png", 2, 1, new int[]{10, 20});
        var fast = new ImageData("fast.png", 2, 1, new int[]{30, 40});

        List<ImageData> results = engine.processBatch(
                List.of(slow, fast),
                List.of(new FilterStep("ordered-test", Map.of("delta", 5.0))),
                2);

        assertEquals(List.of(
                new ImageData("slow.png", 2, 1, new int[]{15, 25}),
                new ImageData("fast.png", 2, 1, new int[]{35, 45})
        ), results);
    }

    @Test
    void splitsLargeImageAcrossMultipleWorkers() {
        CountDownLatch workersReady = new CountDownLatch(2);
        ImageFilter spyFilter = new ImageFilter() {
            @Override
            public String name() {
                return "spy-test";
            }

            @Override
            public void applyRegion(ImageData src, int[] dst, int startY, int endY, Map<String, Double> params) {
                workersReady.countDown();
                try {
                    assertTrue(workersReady.await(5, TimeUnit.SECONDS), "Expected another region to start concurrently");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                for (int i = startY * src.width(); i < endY * src.width(); i++) {
                    dst[i] = src.argbAt(i);
                }
            }
        };

        var engine = new ForkJoinEngine(new FilterRegistry(List.of(spyFilter)));
        int width = 32;
        int height = 513;
        ImageData image = new ImageData("large.png", width, height, new Random(3).ints(width * height).toArray());

        assertEquals(List.of(image),
                engine.processBatch(List.of(image), List.of(new FilterStep("spy-test", Map.of())), 4));
    }

    @Test
    void acceptsEmptyBatchAndEmptyPipeline() {
        var engine = new ForkJoinEngine(new FilterRegistry(List.of()));
        var image = new ImageData("image.png", 1, 1, new int[]{0xff123456});

        assertTrue(engine.processBatch(List.of(), List.of(), 4).isEmpty());
        assertSame(image, engine.processBatch(List.of(image), List.of(), 4).getFirst());
    }

    @Test
    void rejectsInvalidThreadCountAndUnknownFilter() {
        var engine = new ForkJoinEngine(new FilterRegistry(List.of()));

        assertThrows(IllegalArgumentException.class, () -> engine.processBatch(List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> engine.processBatch(List.of(),
                List.of(new FilterStep("unknown", Map.of())), 2));
    }

    @Test
    void unwrapsAndPropagatesFilterExceptions() {
        ImageFilter failingFilter = new ImageFilter() {
            @Override
            public String name() {
                return "failing-test";
            }

            @Override
            public void applyRegion(ImageData src, int[] dst, int startY, int endY, Map<String, Double> params) {
                throw new IllegalArgumentException("bad parameter");
            }
        };

        var engine = new ForkJoinEngine(new FilterRegistry(List.of(failingFilter)));
        List<ImageData> batch = List.of(
                new ImageData("1.png", 1, 1, new int[]{1}),
                new ImageData("2.png", 1, 1, new int[]{2})
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.processBatch(batch, List.of(new FilterStep("failing-test", Map.of())), 2));
        assertEquals("bad parameter", error.getMessage());
    }

    @Test
    void reportsLaterFailureWhileEarlierImageIsStillRunning() throws Exception {
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        FilterFailure failure = new FilterFailure("filter failed", new IllegalArgumentException("nested cause"));
        ImageFilter filter = filter("finish-order", image -> {
            if (image.name().equals("first")) {
                firstStarted.countDown();
                releaseFirst.join();
            } else {
                try {
                    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw failure;
            }
        });
        var engine = new ForkJoinEngine(new FilterRegistry(List.of(filter)));
        var result = new FutureTask<>(() -> engine.processBatch(List.of(
                new ImageData("first", 1, 1, new int[]{1}),
                new ImageData("second", 1, 1, new int[]{2})),
                List.of(new FilterStep(filter.name(), Map.of())), 2));
        Thread.ofPlatform().daemon().start(result);

        try {
            var completed = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertSame(failure, completed.getCause());
        } finally {
            releaseFirst.complete(null);
        }
    }

    @Test
    void preservesFilterExceptionWithItsCause() {
        FilterFailure failure = new FilterFailure("filter failed", new IllegalArgumentException("nested cause"));
        ImageFilter filter = filter("nested-exception", image -> { throw failure; });
        var engine = new ForkJoinEngine(new FilterRegistry(List.of(filter)));

        assertSame(failure, assertThrows(FilterFailure.class, () -> engine.processBatch(
                List.of(new ImageData("image", 1, 1, new int[]{1})),
                List.of(new FilterStep(filter.name(), Map.of())), 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"forkjoin", "threadpool"})
    void interruptReturnsPromptlyAndSkipsLaterStages(String mode) throws Exception {
        CompletableFuture<Thread> workerStarted = new CompletableFuture<>();
        CompletableFuture<Void> releaseWorker = new CompletableFuture<>();
        AtomicBoolean laterStageRan = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        ImageFilter blocking = filter("blocking", image -> {
            workerStarted.complete(Thread.currentThread());
            releaseWorker.join();
        });
        ImageFilter later = filter("later", image -> laterStageRan.set(true));
        var registry = new FilterRegistry(List.of(blocking, later));
        ProcessingEngine engine = mode.equals("forkjoin") ? new ForkJoinEngine(registry) : new ThreadPoolEngine(registry);
        Thread caller = Thread.ofPlatform().daemon().start(() -> {
            try {
                engine.processBatch(List.of(new ImageData("image", 1, 1, new int[]{1})),
                        List.of(new FilterStep(blocking.name(), Map.of()), new FilterStep(later.name(), Map.of())), 1);
            } catch (Throwable e) {
                failure.set(e);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });

        Thread worker = workerStarted.get(5, TimeUnit.SECONDS);
        try {
            caller.interrupt();
            assertTrue(caller.join(Duration.ofSeconds(3)), "Interrupted batch caller did not return");
            assertInstanceOf(InterruptedException.class,
                    assertInstanceOf(IllegalStateException.class, failure.get()).getCause());
            assertTrue(interruptPreserved.get());
        } finally {
            releaseWorker.complete(null);
        }
        assertTrue(worker.join(Duration.ofSeconds(5)), "Worker did not stop after cancellation");
        assertFalse(laterStageRan.get(), "A canceled image started the next filter");
    }

    private static ImageFilter filter(String name, Consumer<ImageData> apply) {
        return new ImageFilter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void applyRegion(ImageData src, int[] dst, int startY, int endY, Map<String, Double> params) {
                apply.accept(src);
            }
        };
    }

    private static final class FilterFailure extends RuntimeException {
        FilterFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
