package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class SobelEdgeFilter implements ImageFilter {
    private static final double DEFAULT_SCALE = 1.0;
    private static final double DEFAULT_THRESHOLD = 0.0;
    private static final double MAX_CHANNEL = 255.0;
    private static final double LUMA_UNITS = 1000.0;

    @Override
    public String name() {
        return "sobel";
    }

    @Override
    public Set<String> parameterNames() {
        return Set.of("scale", "threshold");
    }

    @Override
    public void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dstPixels, "dstPixels");
        Objects.requireNonNull(params, "params");

        int width = src.width();
        int height = src.height();
        if (startY < 0 || endY < startY || endY > height || dstPixels.length != width * height) {
            throw new IllegalArgumentException("Expected a valid row range and a full-size destination");
        }

        double scale = params.getOrDefault("scale", DEFAULT_SCALE);
        double threshold = params.getOrDefault("threshold", DEFAULT_THRESHOLD);
        if (!Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("scale must be finite and positive");
        }
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > MAX_CHANNEL) {
            throw new IllegalArgumentException("threshold must be finite and in [0, 255]");
        }
        if (startY == endY) {
            return;
        }

        // Ring buffer holds only the 3 luminance rows needed for the current 3x3 vertical window.
        int[][] lumaRows = new int[Math.min(height, 3)][];
        int nextRow = Math.max(0, startY - 1);
        for (int y = startY; y < endY; y++) {
            int lastNeededRow = Math.min(height - 1, y + 1);
            while (nextRow <= lastNeededRow) {
                int slot = nextRow % lumaRows.length;
                if (lumaRows[slot] == null) {
                    lumaRows[slot] = new int[width];
                }
                computeLuminanceRow(src, nextRow, lumaRows[slot]);
                nextRow++;
            }

            int[] top = lumaRows[Math.max(0, y - 1) % lumaRows.length];
            int[] mid = lumaRows[y % lumaRows.length];
            int[] bot = lumaRows[Math.min(height - 1, y + 1) % lumaRows.length];
            applySobelRow(src, y, top, mid, bot, scale, threshold, dstPixels);
        }
    }

    private static void computeLuminanceRow(ImageData src, int y, int[] row) {
        int width = src.width();
        int rowStart = y * width;
        for (int x = 0; x < width; x++) {
            int argb = src.argbAt(rowStart + x);
            int r = (argb >>> 16) & 0xff;
            int g = (argb >>> 8) & 0xff;
            int b = argb & 0xff;
            // Keep luma in thousandths so a flat neighborhood has an exact zero gradient.
            row[x] = 299 * r + 587 * g + 114 * b;
        }
    }

    private static void applySobelRow(ImageData src, int y,
                                      int[] top, int[] mid, int[] bot,
                                      double scale, double threshold, int[] dstPixels) {
        int width = src.width();
        int rowStart = y * width;
        for (int x = 0; x < width; x++) {
            int left = Math.max(0, x - 1);
            int right = Math.min(width - 1, x + 1);

            int gx = top[right] - top[left] + 2 * (mid[right] - mid[left]) + bot[right] - bot[left];
            int gy = bot[left] - top[left] + 2 * (bot[x] - top[x]) + bot[right] - top[right];

            double magnitude = (Math.sqrt((long) gx * gx + (long) gy * gy) / LUMA_UNITS) * scale;
            int intensity = magnitude < threshold ? 0 : Math.clamp(Math.round(magnitude), 0, 255);
            int index = rowStart + x;
            int alpha = src.argbAt(index) & 0xff000000;
            dstPixels[index] = alpha | (intensity << 16) | (intensity << 8) | intensity;
        }
    }
}
