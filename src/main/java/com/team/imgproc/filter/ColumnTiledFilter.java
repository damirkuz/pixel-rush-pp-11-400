package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;

import java.util.Map;

/** An optional execution path for filters that benefit from processing full-height column ranges. */
public interface ColumnTiledFilter extends ImageFilter {
    /**
     * Prepares a batch step once. The returned operation can run on disjoint column ranges concurrently.
     * Each operation writes only its columns and may read anywhere in the immutable source.
     */
    ColumnOperation prepareColumns(ImageData src, int[] destination, Map<String, Double> params, Runnable checkCancelled);

    @FunctionalInterface
    interface ColumnOperation {
        void apply(int startX, int endX);
    }
}
