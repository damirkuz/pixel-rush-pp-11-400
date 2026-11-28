package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MotionBlurFilter implements ImageFilter {
    private static final int MAX_RADIUS = 1024;

    @Override
    public String name() {
        return "motion";
    }

    @Override
    public void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params) {
        int width = src.width();
        int height = src.height();
        if (startY < 0 || endY < startY || endY > height || dstPixels.length != width * height) {
            throw new IllegalArgumentException("Expected a valid row range and a full-size destination");
        }
        double radius = params.getOrDefault("radius", 3.0);
        if (!Double.isFinite(radius) || radius < 0 || radius > MAX_RADIUS || radius != Math.rint(radius)) {
            throw new IllegalArgumentException("radius must be an integer in [0, " + MAX_RADIUS + "]");
        }
        double angle = params.getOrDefault("angle", 0.0);
        if (!Double.isFinite(angle)) {
            throw new IllegalArgumentException("angle must be finite");
        }

        int halfLength = (int) radius;
        int samples = 2 * halfLength + 1;
        double direction = angle % 180.0;
        double radians = Math.toRadians(direction < 0 ? direction + 180.0 : direction);
        int[] dx = lineOffsets(halfLength, Math.cos(radians));
        int[] dy = lineOffsets(halfLength, Math.sin(radians));

        for (int y = startY; y < endY; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int i = 0; i < samples; i++) {
                    int nx = Math.clamp((long) x + dx[i], 0, width - 1);
                    int ny = Math.clamp((long) y + dy[i], 0, height - 1);
                    int pixel = src.argbAt(ny * width + nx);
                    red += (pixel >>> 16) & 0xff;
                    green += (pixel >>> 8) & 0xff;
                    blue += pixel & 0xff;
                }
                int index = y * width + x;
                int alpha = src.argbAt(index) & 0xff000000;
                dstPixels[index] = alpha | (((red + halfLength) / samples) << 16)
                        | (((green + halfLength) / samples) << 8) | ((blue + halfLength) / samples);
            }
        }
    }

    private static int[] lineOffsets(int radius, double direction) {
        int[] offsets = new int[2 * radius + 1];
        for (int distance = 1; distance <= radius; distance++) {
            int offset = (int) Math.round(distance * direction);
            offsets[radius + distance] = offset;
            offsets[radius - distance] = -offset;
        }
        return offsets;
    }
}
