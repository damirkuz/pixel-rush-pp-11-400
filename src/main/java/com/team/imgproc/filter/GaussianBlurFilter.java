package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

@Component
public class GaussianBlurFilter implements ColumnTiledFilter {
    private static final int DEFAULT_RADIUS = 2;
    private static final int MAX_RADIUS = 1024;
    private static final double DEFAULT_SIGMA = 1.0;
    private static final int RGB_CHANNELS = 3;

    @Override
    public String name() {
        return "gaussian";
    }

    @Override
    public Set<String> parameterNames() {
        return Set.of("radius", "sigma");
    }

    @Override
    public void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params) {
        int width = src.width();
        int height = src.height();
        if (startY < 0 || endY < startY || endY > height || dstPixels.length != width * height) {
            throw new IllegalArgumentException("Expected a valid row range and a full-size destination");
        }
        var weights = configuredKernel(params);
        blur(src, dstPixels, 0, width, startY, endY, weights, GaussianBlurFilter::checkInterrupted);
    }

    @Override
    public ColumnOperation prepareColumns(ImageData src, int[] dstPixels, Map<String, Double> params,
                                          Runnable checkCancelled) {
        int width = src.width();
        if (dstPixels.length != width * src.height()) {
            throw new IllegalArgumentException("Expected a full-size destination");
        }
        var weights = configuredKernel(params);
        return (startX, endX) -> {
            if (startX < 0 || endX < startX || endX > width) {
                throw new IllegalArgumentException("Expected a valid column range");
            }
            blur(src, dstPixels, startX, endX, 0, src.height(), weights, checkCancelled);
        };
    }

    private static double[] configuredKernel(Map<String, Double> params) {
        double requestedRadius = params.getOrDefault("radius", (double) DEFAULT_RADIUS);
        double sigma = params.getOrDefault("sigma", DEFAULT_SIGMA);
        if (requestedRadius < 0 || requestedRadius > MAX_RADIUS
                || requestedRadius != Math.rint(requestedRadius)) {
            throw new IllegalArgumentException("radius must be an integer from 0 to " + MAX_RADIUS);
        }
        if (!Double.isFinite(sigma) || sigma <= 0) {
            throw new IllegalArgumentException("sigma must be finite and positive");
        }
        return kernel((int) requestedRadius, sigma);
    }

    private static void blur(ImageData src, int[] dstPixels, int startX, int endX, int startY, int endY,
                             double[] weights, Runnable checkCancelled) {
        if (startX == endX || startY == endY) {
            return;
        }
        int width = src.width();
        int height = src.height();
        int radius = weights.length / 2;
        if (radius == 0) {
            for (int y = startY; y < endY; y++) {
                checkCancelled.run();
                int row = y * width;
                for (int x = startX; x < endX; x++) {
                    dstPixels[row + x] = src.argbAt(row + x);
                }
            }
            return;
        }

        // Keep only the rows inside the current vertical window, for this tile's columns.
        int rowChannels = Math.multiplyExact(endX - startX, RGB_CHANNELS);
        double[][] horizontalRows = new double[Math.min(height, weights.length)][];
        double[][] verticalWindow = new double[weights.length][];
        int nextRow = Math.max(0, startY - radius);
        for (int y = startY; y < endY; y++) {
            checkCancelled.run();
            int lastNeededRow = Math.clamp((long) y + radius, 0, height - 1);
            while (nextRow <= lastNeededRow) {
                checkCancelled.run();
                int slot = nextRow % horizontalRows.length;
                if (horizontalRows[slot] == null) {
                    horizontalRows[slot] = new double[rowChannels];
                }
                blurHorizontally(src, nextRow, startX, endX, weights, radius, horizontalRows[slot]);
                nextRow++;
            }
            for (int k = 0; k < weights.length; k++) {
                int neighborY = Math.clamp((long) y + k - radius, 0, height - 1);
                verticalWindow[k] = horizontalRows[neighborY % horizontalRows.length];
            }
            blurVertically(src, y, startX, endX, weights, verticalWindow, dstPixels);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Gaussian blur was interrupted");
        }
    }

    private static double[] kernel(int radius, double sigma) {
        double[] weights = new double[2 * radius + 1];
        weights[radius] = 1;
        double total = 1;
        for (int offset = 1; offset <= radius; offset++) {
            double distance = offset / sigma;
            double weight = Math.exp(-0.5 * distance * distance);
            weights[radius - offset] = weight;
            weights[radius + offset] = weight;
            total += 2 * weight;
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] /= total;
        }
        return weights;
    }

    private static void blurHorizontally(ImageData src, int y, int startX, int endX,
                                         double[] weights, int radius, double[] row) {
        int width = src.width();
        int rowStart = y * width;
        for (int x = startX; x < endX; x++) {
            double red = 0;
            double green = 0;
            double blue = 0;
            for (int k = 0; k < weights.length; k++) {
                int neighborX = Math.clamp((long) x + k - radius, 0, width - 1);
                int argb = src.argbAt(rowStart + neighborX);
                double weight = weights[k];
                red += ((argb >>> 16) & 0xff) * weight;
                green += ((argb >>> 8) & 0xff) * weight;
                blue += (argb & 0xff) * weight;
            }
            int channel = (x - startX) * RGB_CHANNELS;
            row[channel] = red;
            row[channel + 1] = green;
            row[channel + 2] = blue;
        }
    }

    private static void blurVertically(ImageData src, int y, int startX, int endX, double[] weights,
                                       double[][] verticalWindow, int[] dstPixels) {
        int width = src.width();
        int rowStart = y * width;
        for (int x = startX; x < endX; x++) {
            double red = 0;
            double green = 0;
            double blue = 0;
            int channel = (x - startX) * RGB_CHANNELS;
            for (int k = 0; k < weights.length; k++) {
                double[] row = verticalWindow[k];
                double weight = weights[k];
                red += row[channel] * weight;
                green += row[channel + 1] * weight;
                blue += row[channel + 2] * weight;
            }
            int index = rowStart + x;
            int alpha = src.argbAt(index) & 0xff000000;
            dstPixels[index] = alpha | ((int) Math.round(red) << 16)
                    | ((int) Math.round(green) << 8) | (int) Math.round(blue);
        }
    }
}
