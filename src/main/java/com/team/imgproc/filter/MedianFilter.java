package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

@Component
public class MedianFilter implements ImageFilter {
    private static final double DEFAULT_RADIUS = 1.0;
    private static final int MAX_RADIUS = 10;

    @Override
    public String name() {
        return "median";
    }

    @Override
    public void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params) {
        Objects.requireNonNull(src,"src");
        Objects.requireNonNull(dstPixels, "dstPixels");
        Objects.requireNonNull(params, "params");

        int width = src.width();
        int height = src.height();
        if (startY < 0 || endY < startY || endY > height || dstPixels.length != width * height) {
            throw new IllegalArgumentException("Expected a valid row range and a full-size destination");
        }

        double rawRadius = params.getOrDefault("radius", DEFAULT_RADIUS);
        if (!Double.isFinite(rawRadius) || rawRadius < 0 || rawRadius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be finite and in [0, " + MAX_RADIUS + "]");
        }
        int radius = (int) Math.round(rawRadius);
        if (Math.abs(rawRadius - radius) > 1e-9) {
            throw new IllegalArgumentException("radius must be an integer");
        }
        if (startY == endY) {
            return;
        }

        int windowSize = 2 * radius + 1;
        int windowArea = windowSize * windowSize;
        int medianIndex = windowArea / 2;

        int[] redBuffer = new int[windowArea];
        int[] greenBuffer = new int[windowArea];
        int[] blueBuffer = new int[windowArea];

        for (int y = startY; y < endY; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                int cursor = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny = Math.clamp((long) y + dy, 0, height - 1);
                    int neighborRowStart = ny * width;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = Math.clamp((long) x + dx, 0, width - 1);
                        int argb = src.argbAt(neighborRowStart + nx);
                        redBuffer[cursor] = (argb >>> 16) & 0xff;
                        greenBuffer[cursor] = (argb >>> 8) & 0xff;
                        blueBuffer[cursor] = argb & 0xff;
                        cursor++;
                    }
                }

                Arrays.sort(redBuffer, 0, windowArea);
                Arrays.sort(greenBuffer, 0, windowArea);
                Arrays.sort(blueBuffer, 0, windowArea);

                int index = rowStart + x;
                int alpha = src.argbAt(index) & 0xff000000;
                int r = redBuffer[medianIndex];
                int g = greenBuffer[medianIndex];
                int b = blueBuffer[medianIndex];
                dstPixels[index] = alpha | (r << 16) | (g << 8) | b;
            }
        }
    }
}