package com.team.imgproc.model;

import java.util.Arrays;
import java.util.Objects;

/** Immutable image with packed ARGB pixels stored in row-major order. */
public record ImageData(String name, int width, int height, int[] pixels) {
    public ImageData {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pixels, "pixels");
        if (name.isBlank() || width <= 0 || height <= 0 || (long) width * height != pixels.length) {
            throw new IllegalArgumentException("Expected a name, positive dimensions and width * height pixels");
        }
        pixels = pixels.clone();
    }

    @Override
    public int[] pixels() {
        return pixels.clone();
    }

    /** Reads a pixel at index y * width + x without copying the image. */
    public int argbAt(int index) {
        return pixels[index];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageData image
                && width == image.width
                && height == image.height
                && name.equals(image.name)
                && Arrays.equals(pixels, image.pixels);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(name, width, height) + Arrays.hashCode(pixels);
    }
}
