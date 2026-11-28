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

class SobelEdgeFilterTest {
    private final SobelEdgeFilter filter = new SobelEdgeFilter();

    @Test
    void agreesWithDirectTwoDimensionalConvolution() {
        for (int[] dimensions : new int[][]{{7, 9}, {1, 4}, {5, 1}, {1, 1}}) {
            int width = dimensions[0];
            int height = dimensions[1];
            ImageData image = randomImage(width, height);
            int[] result = new int[width * height];

            filter.applyRegion(image, result, 0, height, Map.of());
            assertArrayEquals(reference(image, 1.0, 0.0), result,
                    "default params: width=" + width + ", height=" + height);

            filter.applyRegion(image, result, 0, height, Map.of("scale", 0.5, "threshold", 40.0));
            assertArrayEquals(reference(image, 0.5, 40.0), result,
                    "custom params: width=" + width + ", height=" + height);
        }
    }

    @Test
    void disjointConcurrentStripsMatchFullImageExactly() throws Exception {
        int width = 19;
        int height = 25;
        ImageData image = randomImage(width, height);
        Map<String, Double> params = Map.of("scale", 1.25, "threshold", 15.0);
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
        int[] expected = reference(image, 1.0, 0.0);
        Arrays.fill(expected, 0, 18, 0);
        Arrays.fill(expected, 45, 72, 0);

        filter.applyRegion(image, destination, 2, 5, Map.of());
        filter.applyRegion(image, destination, 8, 8, Map.of());

        assertArrayEquals(expected, destination);
    }

    @Test
    void uniformImageProducesZeroGradientWithPreservedAlpha() {
        int[] pixels = new int[16];
        Arrays.fill(pixels, 0x7a4488cc);
        ImageData image = new ImageData("solid.png", 4, 4, pixels);
        int[] result = new int[16];

        filter.applyRegion(image, result, 0, 4, Map.of());

        int[] expected = new int[16];
        Arrays.fill(expected, 0x7a000000);
        assertArrayEquals(expected, result);
    }

    @Test
    void keepsEdgesExactlyAtTheThreshold() {
        var image = new ImageData("edge.png", 2, 1, new int[]{0xff000000, 0xff010101});
        int[] result = new int[2];

        filter.applyRegion(image, result, 0, 1, Map.of("threshold", 4.0));

        assertArrayEquals(new int[]{0xff040404, 0xff040404}, result);
    }

    @Test
    void largeScaleDoesNotAmplifyRoundingNoiseOnAUniformImage() {
        var image = new ImageData("solid.png", 1, 1, new int[]{0xff000001});
        int[] result = new int[1];

        filter.applyRegion(image, result, 0, 1, Map.of("scale", 1e17));

        assertArrayEquals(new int[]{0xff000000}, result);
    }

    @Test
    void rejectsInvalidParametersAndRegionBounds() {
        ImageData image = randomImage(5, 4);
        int[] result = new int[20];

        for (double scale : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> filter.applyRegion(image, result, 0, 4, Map.of("scale", scale)));
        }
        for (double threshold : new double[]{-0.1, 255.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> filter.applyRegion(image, result, 0, 4, Map.of("threshold", threshold)));
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

    private static int[] reference(ImageData source, double scale, double threshold) {
        int width = source.width();
        int height = source.height();
        int[] result = new int[width * height];
        int[][] kernelX = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
        int[][] kernelY = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double gx = 0;
                double gy = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = Math.clamp((long) y + dy, 0, height - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = Math.clamp((long) x + dx, 0, width - 1);
                        int argb = source.argbAt(ny * width + nx);
                        double luma = 0.299 * ((argb >>> 16) & 0xff)
                                + 0.587 * ((argb >>> 8) & 0xff)
                                + 0.114 * (argb & 0xff);
                        gx += luma * kernelX[dy + 1][dx + 1];
                        gy += luma * kernelY[dy + 1][dx + 1];
                    }
                }
                double magnitude = Math.sqrt(gx * gx + gy * gy) * scale;
                int intensity = magnitude < threshold ? 0 : Math.clamp(Math.round(magnitude), 0, 255);
                int index = y * width + x;
                int alpha = source.argbAt(index) & 0xff000000;
                result[index] = alpha | (intensity << 16) | (intensity << 8) | intensity;
            }
        }
        return result;
    }
}
