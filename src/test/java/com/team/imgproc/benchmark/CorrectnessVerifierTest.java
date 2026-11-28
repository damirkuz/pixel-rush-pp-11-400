package com.team.imgproc.benchmark;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PixelRushApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CorrectnessVerifierTest {
    @Autowired
    private CorrectnessVerifier verifier;

    @Autowired
    private List<ProcessingEngine> engines;

    @Autowired
    private List<ImageFilter> filters;

    @Test
    void reportsExactMatchForIdenticalImagesAndBatches() {
        ImageData img1 = new ImageData("a.png", 2, 2, new int[]{0xff102030, 0xff405060, 0xff708090, 0xffa0b0c0});
        ImageData img2 = new ImageData("a.png", 2, 2, new int[]{0xff102030, 0xff405060, 0xff708090, 0xffa0b0c0});

        CorrectnessVerifier.Report report = verifier.verifyBatch(List.of(img1), List.of(img2));

        assertTrue(report.exactMatch());
        assertEquals(4, report.totalPixels());
        assertEquals(0, report.mismatchedPixels());
        assertEquals(0, report.maxDiff());
        assertEquals(0.0, report.mae());
        assertTrue(report.isWithinTolerance(0, 0.0));
    }

    @Test
    void computesMaxDiffMismatchedPixelsAndMaeCorrectly() {
        ImageData expected = new ImageData("test.png", 2, 1, new int[]{
                0xff102030,
                0xff405060
        });
        // Pixel 0: R differs by 6, G differs by 3, B differs by 0 -> sum = 9, max = 6
        // Pixel 1: identical -> sum = 0, max = 0
        ImageData actual = new ImageData("test.png", 2, 1, new int[]{
                0xff162330,
                0xff405060
        });

        CorrectnessVerifier.Report report = verifier.verifyImage(expected, actual);

        assertFalse(report.exactMatch());
        assertEquals(2, report.totalPixels());
        assertEquals(1, report.mismatchedPixels());
        assertEquals(6, report.maxDiff());
        // totalChannelDiff = 9 across 2 pixels * 3 channels = 6 channels -> MAE = 9 / 6.0 = 1.5
        assertEquals(1.5, report.mae(), 1e-9);
        assertTrue(report.isWithinTolerance(6, 1.5));
        assertFalse(report.isWithinTolerance(5, 1.5));
    }

    @Test
    void handlesEmptyBatchAndRejectsMismatchedMetadata() {
        CorrectnessVerifier.Report emptyReport = verifier.verifyBatch(List.of(), List.of());
        assertTrue(emptyReport.exactMatch());
        assertEquals(0, emptyReport.totalPixels());

        ImageData imgA = new ImageData("a.png", 2, 2, new int[4]);
        ImageData imgB = new ImageData("b.png", 2, 2, new int[4]);
        ImageData imgSize = new ImageData("a.png", 4, 1, new int[4]);

        assertThrows(IllegalArgumentException.class, () -> verifier.verifyBatch(List.of(imgA), List.of()));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyImage(imgA, imgB));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyImage(imgA, imgSize));
    }

    @Test
    void allRegisteredFiltersAndEnginesMatchSequentialExactly() {
        ProcessingEngine sequential = engines.stream()
                .filter(e -> e.mode().equals("sequential"))
                .findFirst()
                .orElseThrow();
        List<ProcessingEngine> parallelEngines = engines.stream()
                .filter(e -> !e.mode().equals("sequential"))
                .toList();

        assertFalse(parallelEngines.isEmpty(), "Expected parallel engines to be registered");
        assertTrue(filters.size() >= 4, "Expected at least 4 filters (gaussian, sobel, median, sharpen)");

        Random random = new Random(123);
        // Height 140 > 64 ensures ForkJoinEngine splits work into multiple recursive row tasks
        List<ImageData> batch = List.of(
                new ImageData("first.png", 24, 140, random.ints(24 * 140).toArray()),
                new ImageData("second.png", 32, 96, random.ints(32 * 96).toArray()),
                new ImageData("third.png", 15, 20, random.ints(15 * 20).toArray())
        );

        for (ImageFilter filter : filters) {
            List<FilterStep> singleFilterPipeline = List.of(new FilterStep(filter.name(), Map.of()));
            List<ImageData> reference = sequential.processBatch(batch, singleFilterPipeline, 1);

            for (ProcessingEngine engine : parallelEngines) {
                for (int threads : new int[]{1, 2, 4}) {
                    List<ImageData> actual = engine.processBatch(batch, singleFilterPipeline, threads);
                    CorrectnessVerifier.Report report = verifier.verifyBatch(reference, actual);
                    assertTrue(report.exactMatch(),
                            "Mismatch for filter=" + filter.name() + ", engine=" + engine.mode() + ", threads=" + threads);
                    assertEquals(0, report.maxDiff());
                    assertEquals(0.0, report.mae());
                }
            }
        }

        // Also test multi-filter pipeline across all engines
        List<FilterStep> combinedPipeline = List.of(
                new FilterStep("median", Map.of("radius", 1.0)),
                new FilterStep("gaussian", Map.of("radius", 2.0, "sigma", 1.2)),
                new FilterStep("sharpen", Map.of("amount", 1.5)),
                new FilterStep("sobel", Map.of("scale", 1.0, "threshold", 15.0))
        );
        List<ImageData> expectedCombined = sequential.processBatch(batch, combinedPipeline, 1);
        for (ProcessingEngine engine : parallelEngines) {
            List<ImageData> actualCombined = engine.processBatch(batch, combinedPipeline, 4);
            CorrectnessVerifier.Report report = verifier.verifyBatch(expectedCombined, actualCombined);
            assertTrue(report.exactMatch(), "Combined pipeline mismatch on engine=" + engine.mode());
        }
    }
}
