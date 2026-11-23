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

class SharpenFilterTest {
    private final SharpenFilter filter = new SharpenFilter();

    @Test
    void agreesWithDirectTwoDimensionalConvolution() {
        for (int[] dimensions : new int[][]{{7, 9}, {1, 4}, {5, 1}, {1, 1}}) {
            int width = dimensions[0];
            int height = dimensions[1];
            ImageData image = randomImage(width, height);
            int[] result = new int[width * height];

            filter.applyRegion(image, result, 0, height, Map.of());
            assertArrayEquals(reference(image, 1.0), result,
                    "default amount: width=" + width + ", height=" + height);

            filter.applyRegion(image, result, 0, height, Map.of("amount", 2.5));
            assertArrayEquals(reference(image, 2.5), result,
                    "custom amount: width=" + width + ", height=" + height);
        }
    }

    @Test
    void disjointConcurrentStripsMatchFullImageExactly() throws Exception {
        int width = 19;
        int height = 25;
        ImageData image = randomImage(width, height);
        Map<String, Double> params = Map.of("amount", 1.5);
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
        int[] expected = reference(image, 1.0);
        Arrays.fill(expected, 0, 18, 0);
        Arrays.fill(expected, 45, 72, 0);

        filter.applyRegion(image, destination, 2, 5, Map.of());
        filter.applyRegion(image, destination, 8, 8, Map.of());

        assertArrayEquals(expected, destination);
    }

    @Test
    void uniformImageAndZeroAmountPreserveColorsAndAlpha() {
        int[] pixels = new int[16];
        Arrays.fill(pixels, 0x7a4488cc);
        ImageData solid = new ImageData("solid.png", 4, 4, pixels);
        int[] solidResult = new int[16];

        filter.applyRegion(solid, solidResult, 0, 4, Map.of("amount", 2.0));
        assertArrayEquals(pixels, solidResult);

        ImageData random = randomImage(6, 5);
        int[] zeroAmountResult = new int[30];
        filter.applyRegion(random, zeroAmountResult, 0, 5, Map.of("amount", 0.0));
        assertArrayEquals(random.pixels(), zeroAmountResult);
    }

    @Test
    void rejectsInvalidParametersAndRegionBounds() {
        ImageData image = randomImage(5, 4);
        int[] result = new int[20];

        for (double amount : new double[]{-0.1, -1.0, 10.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> filter.applyRegion(image, result, 0, 4, Map.of("amount", amount)));
        }

        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, -1, 2, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, 2, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, result, 0, 5, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> filter.applyRegion(image, new int[19], 0, 4, Map.of()));
    }

    private static ImageData randomImage(int width, int height) {
        int[] pixels = new Random(42).ints(width * height).toArray();
        return new ImageData("random.png", width, height, pixels);
    }

    private static int[] reference(ImageData source, double amount) {
        int width = source.width();
        int height = source.height();
        int[] result = new int[width * height];
        double[][] kernel = {
                {0.0, -amount, 0.0},
                {-amount, 1.0 + 4.0 * amount, -amount},
                {0.0, -amount, 0.0}
        };

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double red = 0.0;
                double green = 0.0;
                double blue = 0.0;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = Math.clamp((long) y + dy, 0, height - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = Math.clamp((long) x + dx, 0, width - 1);
                        int argb = source.argbAt(ny * width + nx);
                        double w = kernel[dy + 1][dx + 1];
                        red += ((argb >>> 16) & 0xff) * w;
                        green += ((argb >>> 8) & 0xff) * w;
                        blue += (argb & 0xff) * w;
                    }
                }
                int r = Math.clamp(Math.round(red), 0, 255);
                int g = Math.clamp(Math.round(green), 0, 255);
                int b = Math.clamp(Math.round(blue), 0, 255);
                int index = y * width + x;
                int alpha = source.argbAt(index) & 0xff000000;
                result[index] = alpha | (r << 16) | (g << 8) | b;
            }
        }
        return result;
    }
}
