package com.team.imgproc.pipeline;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = PixelRushApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ImagePipelineTest {
    @Autowired
    private ImagePipeline pipeline;

    @Test
    void executesStepsInOrderAndMatchesBothParallelEngines() {
        int[] pixels = new int[130 * 3];
        Arrays.fill(pixels, 0xff000000);
        pixels[64 * 3 + 1] = 0xffffffff;
        var first = new ImageData("first.png", 3, 130, pixels);
        var second = new ImageData("second.png", 1, 1, new int[]{0xff778899});
        List<ImageData> images = List.of(first, second);
        var steps = PipelineSpec.parse("motion:radius=1:angle=90,median:radius=1");

        var expected = pipeline.process(images, steps, "sequential", 1);
        assertEquals(0xff000000, expected.getFirst().argbAt(64 * 3 + 1));
        assertEquals(List.of("first.png", "second.png"), expected.stream().map(ImageData::name).toList());
        assertEquals(expected, pipeline.process(images, steps, "threadpool", 2));
        assertEquals(expected, pipeline.process(images, steps, "forkjoin", 2));

        // Swapping blur and sharpening changes clipping near the impulse.
        var forward = PipelineSpec.parse("motion:radius=1,sharpen:amount=1");
        var reverse = PipelineSpec.parse("sharpen:amount=1,motion:radius=1");
        assertNotEquals(pipeline.process(images, forward, "sequential", 1),
                pipeline.process(images, reverse, "sequential", 1));
    }

    @Test
    void rejectsUnknownEnginesAndFiltersEvenForEmptyBatches() {
        var steps = PipelineSpec.parse("motion");
        assertThrows(IllegalArgumentException.class, () -> pipeline.process(List.of(), steps, "missing", 2));
        assertThrows(IllegalArgumentException.class, () -> pipeline.process(List.of(), steps, "forkjoin", 0));
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.process(List.of(), PipelineSpec.parse("missing"), "sequential", 1));
    }

    @Test
    void rejectsMalformedFilterSpecifications() {
        for (String invalid : List.of("", "median,", "motion:radius=", "motion:radius=NaN",
                "motion:radius=1:radius=2", "motion::radius=1")) {
            assertThrows(IllegalArgumentException.class, () -> PipelineSpec.parse(invalid), invalid);
        }
    }

    @Test
    void rejectsMisspelledParameterNamesBeforeProcessingAnyImages() {
        for (String specification : List.of("gaussian:sigam=3", "sobel:scal=1", "median:raduis=1",
                "sharpen:ammount=1", "motion:raduis=24")) {
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> pipeline.process(List.of(), PipelineSpec.parse(specification), "sequential", 1));
            assertTrue(exception.getMessage().contains(specification.split("[:=]")[1]));
        }
    }
}
