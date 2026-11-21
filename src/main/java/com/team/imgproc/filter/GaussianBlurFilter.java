package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GaussianBlurFilter implements ImageFilter {
    private static final int DEFAULT_RADIUS = 2;
    private static final int MAX_RADIUS = 1024;
    private static final double DEFAULT_SIGMA = 1.0;
    private static final int RGB_CHANNELS = 3;

    @Override
    public String name() {
        return "gaussian";
    }

    @Override
    public void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params) {
        int width = src.width();
        int height = src.height();
        if (startY < 0 || endY < startY || endY > height || dstPixels.length != width * height) {
            throw new IllegalArgumentException("Expected a valid row range and a full-size destination");
        }

        double requestedRadius = params.getOrDefault("radius", (double) DEFAULT_RADIUS);
        double sigma = params.getOrDefault("sigma", DEFAULT_SIGMA);
        if (requestedRadius < 0 || requestedRadius > MAX_RADIUS
                || requestedRadius != Math.rint(requestedRadius)) {
            throw new IllegalArgumentException("radius must be an integer from 0 to " + MAX_RADIUS);
        }
        if (!Double.isFinite(sigma) || sigma <= 0) {
            throw new IllegalArgumentException("sigma must be finite and positive");
        }
        if (startY == endY) {
            return;
        }

        int radius = (int) requestedRadius;
        if (radius == 0) {
            for (int index = startY * width; index < endY * width; index++) {
                dstPixels[index] = src.argbAt(index);
            }
            return;
        }

        double[] weights = kernel(radius, sigma);
        // Only rows inside the current vertical window must stay in the ring.
        double[][] horizontalRows = new double[Math.min(height, weights.length)][];
        double[][] verticalWindow = new double[weights.length][];
        int nextRow = Math.max(0, startY - radius);
        for (int y = startY; y < endY; y++) {
            int lastNeededRow = Math.clamp((long) y + radius, 0, height - 1);
            while (nextRow <= lastNeededRow) {
                int slot = nextRow % horizontalRows.length;
                if (horizontalRows[slot] == null) {
                    horizontalRows[slot] = new double[Math.multiplyExact(width, RGB_CHANNELS)];
                }
                blurHorizontally(src, nextRow, weights, radius, horizontalRows[slot]);
                nextRow++;
            }
            for (int k = 0; k < weights.length; k++) {
                int neighborY = Math.clamp((long) y + k - radius, 0, height - 1);
                verticalWindow[k] = horizontalRows[neighborY % horizontalRows.length];
            }
            blurVertically(src, y, weights, verticalWindow, dstPixels);
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

    private static void blurHorizontally(ImageData src, int y, double[] weights, int radius, double[] row) {
        int width = src.width();
        int rowStart = y * width;
        for (int x = 0; x < width; x++) {
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
            int channel = x * RGB_CHANNELS;
            row[channel] = red;
            row[channel + 1] = green;
            row[channel + 2] = blue;
        }
    }

    private static void blurVertically(ImageData src, int y, double[] weights,
                                       double[][] verticalWindow, int[] dstPixels) {
        int width = src.width();
        int rowStart = y * width;
        for (int x = 0; x < width; x++) {
            double red = 0;
            double green = 0;
            double blue = 0;
            int channel = x * RGB_CHANNELS;
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
