package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;

import java.util.Map;
import java.util.Set;

public interface ImageFilter {
    String name();

    /** Names accepted in a configured pipeline. Parameterless filters use the default. */
    default Set<String> parameterNames() {
        return Set.of();
    }

    /**
     * Reads the immutable source and writes rows [startY, endY) of a full-size destination.
     * Coordinates are relative to the whole image. Calls for disjoint regions may run concurrently.
     */
    void applyRegion(ImageData src, int[] dstPixels, int startY, int endY, Map<String, Double> params);
}
