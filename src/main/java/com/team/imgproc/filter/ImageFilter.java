package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;

import java.util.Map;

public interface ImageFilter {
    String name();

    /**
     * Reads the immutable source and writes rows [startY, endY) of a full-size destination.
     * Coordinates are relative to the whole image. Calls for disjoint regions may run concurrently.
     */
    void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params);
}
