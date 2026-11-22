package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MedianFilterTest {
    private final MedianFilter filter = new MedianFilter();

    @Test
    void agreesWithReferenceOnSmallImages() {
        for (int[] dimensions : new int[][]{{1, 1}, {1, 5}, {5, 1}, {5, 5}, {7, 9}}) {
            int width = dimensions[0];
            int height = dimensions[1];
            ImageData image = randomImage(width, height);
            int[] result = new int[width * height];

            filter.applyRegion(image, result, 0, height, Map.of());
            assertArrayEquals(reference(image, 1), result,
                    "default radius: width=" + width + ", height=" + height);

            filter.applyRegion(image, result, 0, height, Map.of("radius", 2.0));
            assertArrayEquals(reference(image, 2), result,
                    "radius=2: width=" + width + ", height=" + height);
        }
    }

    @Test
    void disjointConcurrentStripsMatchFullImageExactly() throws Exception {
        int width = 19;
        int height = 25;
        ImageData image = randomImage(width, height);
        Map<String, Double> params = Map.of("radius", 2.0);
        int[] wholeImage = new int[width * height];
        int[] strips = new int[wholeImage.length];
        filter.applyRegion(image, wholeImage, 0, height, params);

        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = IntStream.range(0, height)
                    .mapToObj(row -> (Callable<Void>) () -> {
                        filter.applyRegion(image, strips, row, row + 1, params);
                        return null;
                    })
                    .toList();
            for (var job : pool.invokeAll(jobs)) {
                job.get();
            }
        }

        assertArrayEquals(wholeImage, strips);
    }

    @Test
    void onlyWritesRequestedRows() {
        ImageData image = randomImage(9, 8);
        int[] destination = new int[72];
        int[] expected = reference(image, 1);
        Arrays.fill(expected, 0, 18, 0);
        Arrays.fill(expected, 45, 72, 0);

        filter.applyRegion(image, destination, 2, 5, Map.of());
        filter.applyRegion(image, destination, 8, 8, Map.of());

        assertArrayEquals(expected, destination);
    }

    @Test
    void uniformImageProducesSameColorWithPreservedAlpha() {
        int[] pixels = new int[16];
        Arrays.fill(pixels, 0x7a4488cc);
        ImageData image = new ImageData("solid.png", 4, 4, pixels);
        int[] result = new int[16];

        filter.applyRegion(image, result, 0, 4, Map.of("radius", 1.0));

        int[] expected = new int[16];
        Arrays.fill(expected, 0x7a4488cc);
        assertArrayEquals(expected, result);
    }

    @Test
    void rejectsInvalidParametersAndRegionBounds() {
        ImageData image = randomImage(5, 4);
        int[] result = new int[20];

        for (double radius : new double[]{-1.0, -0.5, 10.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> filter.applyRegion(image, result, 0, 4, Map.of("radius", radius)));
        }
        // non-integer radius inside valid range
        assertThrows(IllegalArgumentException.class,
                () -> filter.applyRegion(image, result, 0, 4, Map.of("radius", 1.5)));

        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, -1, 2, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, 2, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, 0, 5, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, new int[19], 0, 4, Map.of()));
    }

    private static ImageData randomImage(int width, int height) {
        int[] pixels = new Random(42).ints(width * height).toArray();
        return new ImageData("random.png", width, height, pixels);
    }

    private static int[] reference(ImageData source, int radius) {
        int width = source.width();
        int height = source.height();
        int windowSize = 2 * radius + 1;
        int windowArea = windowSize * windowSize;
        int medianIndex = windowArea / 2;
        int[] result = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] red = new int[windowArea];
                int[] green = new int[windowArea];
                int[] blue = new int[windowArea];
                int cursor = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny = Math.clamp((long) y + dy, 0, height - 1);
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = Math.clamp((long) x + dx, 0, width - 1);
                        int argb = source.argbAt(ny * width + nx);
                        red[cursor] = (argb >>> 16) & 0xff;
                        green[cursor] = (argb >>> 8) & 0xff;
                        blue[cursor] = argb & 0xff;
                        cursor++;
                    }
                }
                Arrays.sort(red);
                Arrays.sort(green);
                Arrays.sort(blue);
                int index = y * width + x;
                int alpha = source.argbAt(index) & 0xff000000;
                result[index] = alpha | (red[medianIndex] << 16) | (green[medianIndex] << 8) | blue[medianIndex];
            }
        }
        return result;
    }
}