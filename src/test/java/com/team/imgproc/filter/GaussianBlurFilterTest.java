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

class GaussianBlurFilterTest {
    private final GaussianBlurFilter filter = new GaussianBlurFilter();

    @Test
    void agreesWithDirectTwoDimensionalConvolution() {
        for (int[] dimensions : new int[][]{{7, 9}, {1, 4}, {5, 1}, {1, 1}}) {
            int width = dimensions[0];
            int height = dimensions[1];
            ImageData image = image(width, height);
            int[] result = new int[width * height];

            for (int radius : new int[]{1, 3, 6}) {
                double sigma = 1.4;
                filter.applyRegion(image, result, 0, height, Map.of("radius", (double) radius, "sigma", sigma));
                assertArrayEquals(reference(image, radius, sigma), result,
                        "width=" + width + ", height=" + height + ", radius=" + radius);
            }
        }
    }

    @Test
    void disjointConcurrentStripsMatchFullImageExactly() throws Exception {
        int width = 17;
        int height = 23;
        ImageData image = image(width, height);
        Map<String, Double> params = Map.of("radius", 4.0, "sigma", 2.5);
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
        ImageData image = image(9, 8);
        int[] destination = new int[72];
        int[] expected = reference(image, 2, 1);
        Arrays.fill(expected, 0, 18, 0);
        Arrays.fill(expected, 45, 72, 0);

        filter.applyRegion(image, destination, 2, 5, Map.of());
        filter.applyRegion(image, destination, 8, 8, Map.of());

        assertArrayEquals(expected, destination);
    }

    @Test
    void zeroRadiusAndTinySigmaAreIdentity() {
        ImageData image = image(5, 4);
        int[] result = new int[20];

        filter.applyRegion(image, result, 0, 4, Map.of("radius", 0.0));
        assertArrayEquals(image.pixels(), result);
        filter.applyRegion(image, result, 0, 4, Map.of("sigma", Double.MIN_VALUE));
        assertArrayEquals(image.pixels(), result);
    }

    @Test
    void rejectsRadiusBeyondSupportedMaximumBeforeAllocatingTheKernel() {
        ImageData image = image(1, 1);
        int[] result = new int[1];

        assertThrows(IllegalArgumentException.class,
                () -> filter.applyRegion(image, result, 0, 1, Map.of("radius", 1025.0)));
        filter.applyRegion(image, result, 0, 1, Map.of("radius", 1024.0));
        assertArrayEquals(image.pixels(), result);
    }

    @Test
    void rejectsInvalidParametersAndRegionBounds() {
        ImageData image = image(5, 4);
        int[] result = new int[20];
        for (double radius : new double[]{-1.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> filter.applyRegion(image, result, 0, 4, Map.of("radius", radius)));
        }
        for (double sigma : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> filter.applyRegion(image, result, 0, 4, Map.of("sigma", sigma)));
        }
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, -1, 2, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, 2, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, 0, 5, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, new int[19], 0, 4, Map.of()));
    }

    private static ImageData image(int width, int height) {
        int[] pixels = new Random(113).ints(width * height).toArray();
        return new ImageData("random.png", width, height, pixels);
    }

    private static int[] reference(ImageData source, int radius, double sigma) {
        int width = source.width();
        int height = source.height();
        int[] result = new int[width * height];
        double[][] weights = new double[2 * radius + 1][2 * radius + 1];
        double total = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                double weight = Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                weights[dy + radius][dx + radius] = weight;
                total += weight;
            }
        }
        for (int index = 0; index < result.length; index++) {
            int argb = source.argbAt(index) & 0xff000000;
            for (int shift : new int[]{0, 8, 16}) {
                double sum = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int y = Math.clamp(index / width + dy, 0, height - 1);
                        int x = Math.clamp(index % width + dx, 0, width - 1);
                        int channel = (source.argbAt(y * width + x) >>> shift) & 0xff;
                        sum += channel * weights[dy + radius][dx + radius];
                    }
                }
                argb |= (int) Math.round(sum / total) << shift;
            }
            result[index] = argb;
        }
        return result;
    }
}
