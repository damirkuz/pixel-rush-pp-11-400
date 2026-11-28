package com.team.imgproc.engine;

import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SequentialEngineTest {
    @Test
    void appliesTheChainInOrderOnTheCallingThreadAndPreservesBatchOrder() {
        List<Thread> invocationThreads = new ArrayList<>();
        var add = filter("add", Integer::sum, invocationThreads);
        var multiply = filter("multiply", (pixel, amount) -> pixel * amount, invocationThreads);
        var engine = new SequentialEngine(new FilterRegistry(List.of(add, multiply)));
        var first = new ImageData("first.png", 1, 2, new int[]{1, 2});
        var second = new ImageData("second.bmp", 2, 1, new int[]{3, 4});

        List<ImageData> results = engine.processBatch(List.of(first, second), List.of(
                new FilterStep("add", Map.of("amount", 5.0)),
                new FilterStep("multiply", Map.of("amount", 3.0))), 4);

        assertEquals("sequential", engine.mode());
        assertEquals(List.of(new ImageData("first.png", 1, 2, new int[]{18, 21}),
                             new ImageData("second.bmp", 2, 1, new int[]{24, 27})), results);
        assertArrayEquals(new int[]{1, 2}, first.pixels());
        assertEquals(java.util.Collections.nCopies(4, Thread.currentThread()), invocationThreads);
    }

    @Test
    void acceptsEmptyBatchAndEmptyPipeline() {
        var engine = new SequentialEngine(new FilterRegistry(List.of()));
        var image = new ImageData("image.png", 1, 1, new int[]{0xff123456});

        assertTrue(engine.processBatch(List.of(), List.of(), 1).isEmpty());
        assertSame(image, engine.processBatch(List.of(image), List.of(), 1).getFirst());
    }

    @Test
    void rejectsInvalidThreadCountAndUnknownFilter() {
        var engine = new SequentialEngine(new FilterRegistry(List.of()));

        assertThrows(IllegalArgumentException.class, () -> engine.processBatch(List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> engine.processBatch(List.of(),
                List.of(new FilterStep("unknown", Map.of())), 1));
    }

    private static ImageFilter filter(String name, IntBinaryOperator operation, List<Thread> threads) {
        return new ImageFilter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void applyRegion(ImageData src, int[] dst, int startY, int endY, Map<String, Double> params) {
                threads.add(Thread.currentThread());
                assertEquals(0, startY);
                assertEquals(src.height(), endY);
                assertEquals(src.width() * src.height(), dst.length);
                int amount = params.get("amount").intValue();
                for (int index = 0; index < dst.length; index++) {
                    dst[index] = operation.applyAsInt(src.argbAt(index), amount);
                }
            }
        };
    }
}
