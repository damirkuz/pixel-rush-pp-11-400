package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class SharpenFilter implements ImageFilter {
    private static final double DEFAULT_AMOUNT = 1.0;
    private static final double MAX_AMOUNT = 10.0;

    @Override
    public String name() {
        return "sharpen";
    }

    @Override
    public Set<String> parameterNames() {
        return Set.of("amount");
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

        double amount = params.getOrDefault("amount", DEFAULT_AMOUNT);
        if (!Double.isFinite(amount) || amount < 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("amount must be finite and in [0, " + MAX_AMOUNT + "]");
        }
        if (startY == endY) {
            return;
        }

        double[][] kernel = {
                {0.0, -amount, 0.0},
                {-amount, 1.0 + 4.0 * amount, -amount},
                {0.0, -amount, 0.0}
        };

        for (int y = startY; y < endY; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                double red = 0.0;
                double green = 0.0;
                double blue = 0.0;

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = Math.clamp((long) y + dy, 0, height - 1);
                    int neighborRowStart = ny * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        double weight = kernel[dy + 1][dx + 1];
                        if (weight == 0.0) {
                            continue;
                        }
                        int nx = Math.clamp((long) x + dx, 0, width - 1);
                        int argb = src.argbAt(neighborRowStart + nx);
                        red += ((argb >>> 16) & 0xff) * weight;
                        green += ((argb >>> 8) & 0xff) * weight;
                        blue += (argb & 0xff) * weight;
                    }
                }

                int r = Math.clamp(Math.round(red), 0, 255);
                int g = Math.clamp(Math.round(green), 0, 255);
                int b = Math.clamp(Math.round(blue), 0, 255);

                int index = rowStart + x;
                int alpha = src.argbAt(index) & 0xff000000;
                dstPixels[index] = alpha | (r << 16) | (g << 8) | b;
            }
        }
    }
}
