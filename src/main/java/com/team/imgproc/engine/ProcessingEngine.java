package com.team.imgproc.engine;

import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;

import java.util.List;

public interface ProcessingEngine {
    String mode();

    /**
     * Applies the ordered pipeline to each image, returning results in input order.
     * The thread count must be positive. All images are already in memory.
     */
    List<ImageData> processBatch(List<ImageData> images, List<FilterStep> pipeline, int threads);
}
