package com.team.imgproc.engine;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.filter.SobelEdgeFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PixelRushApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ThreadPoolEngineTest {
    @Autowired
    private List<ProcessingEngine> engines;

    @Autowired
    private FilterRegistry filterRegistry;

    @Test
    void registersEngineAndSobelFilterInSpringContext() {
        ProcessingEngine threadPool = engines.stream()
                .filter(engine -> engine.mode().equals("threadpool"))
                .findFirst()
                .orElseThrow();
        ImageFilter sobel = filterRegistry.require("sobel");

        assertInstanceOf(ThreadPoolEngine.class, threadPool);
        assertInstanceOf(SobelEdgeFilter.class, sobel);

        Random random = new Random(7);
        List<ImageData> batch = List.of(
                new ImageData("a.png", 16, 12, random.ints(16 * 12).toArray()),
                new ImageData("b.png", 20, 10, random.ints(20 * 10).toArray()),
                new ImageData("c.png", 8, 15, random.ints(8 * 15).toArray())
        );
        List<FilterStep> pipeline = List.of(new FilterStep("sobel", Map.of("scale", 1.1)));

        List<ImageData> singleThread = threadPool.processBatch(batch, pipeline, 1);
        List<ImageData> multiThread = threadPool.processBatch(batch, pipeline, 4);

        assertEquals(singleThread, multiThread);
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

        var engine = new ThreadPoolEngine(new FilterRegistry(List.of(orderedFilter)));
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
    void executesImagesConcurrentlyAcrossRequestedThreads() {
        int workerCount = 4;
        CountDownLatch allStarted = new CountDownLatch(workerCount);
        Set<String> workerNames = ConcurrentHashMap.newKeySet();

        ImageFilter barrierFilter = new ImageFilter() {
            @Override
            public String name() {
                return "barrier-test";
            }

            @Override
            public void applyRegion(ImageData src, int[] dst, int startY, int endY, Map<String, Double> params) {
                workerNames.add(Thread.currentThread().getName());
                allStarted.countDown();
                try {
                    assertTrue(allStarted.await(5, TimeUnit.SECONDS), "Expected all workers to run concurrently");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                for (int i = startY * src.width(); i < endY * src.width(); i++) {
                    dst[i] = src.argbAt(i);
                }
            }
        };

        var engine = new ThreadPoolEngine(new FilterRegistry(List.of(barrierFilter)));
        List<ImageData> batch = List.of(
                new ImageData("1.png", 1, 1, new int[]{1}),
                new ImageData("2.png", 1, 1, new int[]{2}),
                new ImageData("3.png", 1, 1, new int[]{3}),
                new ImageData("4.png", 1, 1, new int[]{4})
        );

        List<ImageData> results = engine.processBatch(batch, List.of(new FilterStep("barrier-test", Map.of())), workerCount);

        assertEquals(batch, results);
        assertEquals(workerCount, workerNames.size());
    }

    @Test
    void acceptsEmptyBatchAndEmptyPipeline() {
        var engine = new ThreadPoolEngine(new FilterRegistry(List.of()));
        var image = new ImageData("image.png", 1, 1, new int[]{0xff123456});

        assertTrue(engine.processBatch(List.of(), List.of(), 4).isEmpty());
        assertSame(image, engine.processBatch(List.of(image), List.of(), 4).getFirst());
    }

    @Test
    void rejectsInvalidThreadCountAndUnknownFilter() {
        var engine = new ThreadPoolEngine(new FilterRegistry(List.of()));

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

        var engine = new ThreadPoolEngine(new FilterRegistry(List.of(failingFilter)));
        List<ImageData> batch = List.of(
                new ImageData("1.png", 1, 1, new int[]{1}),
                new ImageData("2.png", 1, 1, new int[]{2})
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.processBatch(batch, List.of(new FilterStep("failing-test", Map.of())), 2));
        assertEquals("bad parameter", error.getMessage());
    }
}
