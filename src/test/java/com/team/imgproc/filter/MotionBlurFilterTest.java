package com.team.imgproc.filter;

import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MotionBlurFilterTest {
    private final MotionBlurFilter filter = new MotionBlurFilter();

    @Test
    void averagesRgbHorizontallyAndPreservesCenterAlpha() {
        var image = new ImageData("line.png", 3, 1, new int[]{
                0x11000000, 0x22ff6000, 0x330030ff
        });
        int[] result = new int[3];

        filter.applyRegion(image, result, 0, 1, Map.of("radius", 1.0));

        assertArrayEquals(new int[]{0x11552000, 0x22553055, 0x335540aa}, result);
    }

    @Test
    void samplesAcrossRegionBoundaryAndWritesOnlyRequestedRows() {
        var image = new ImageData("column.png", 1, 3, new int[]{
                0xff000000, 0xff000000, 0xffffffff
        });
        int[] result = new int[3];
        Arrays.fill(result, 123);

        filter.applyRegion(image, result, 1, 2, Map.of("radius", 1.0, "angle", 90.0));

        assertArrayEquals(new int[]{123, 0xff555555, 123}, result);
    }

    @Test
    void supportsZeroRadiusAndRejectsInvalidParameters() {
        var image = new ImageData("one.png", 1, 1, new int[]{0x12345678});
        int[] result = new int[1];
        filter.applyRegion(image, result, 0, 1, Map.of("radius", 0.0));
        assertArrayEquals(image.pixels(), result);

        assertThrows(IllegalArgumentException.class,
                () -> filter.applyRegion(image, result, 0, 1, Map.of("radius", 1.5)));
        assertThrows(IllegalArgumentException.class,
                () -> filter.applyRegion(image, result, 0, 1, Map.of("angle", Double.NaN)));
    }
}
