package com.team.imgproc.benchmark;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.model.BenchmarkResult;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PixelRushApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkRunnerTest {
    @TempDir
    Path tempDir;

    @Autowired
    private BenchmarkRunner runner;

    @Test
    void runsComputeAndEndToEndBenchmarksAndExportsCsv() throws IOException {
        List<ImageData> batch = BenchmarkRunner.generateSyntheticBatch(3, 32, 32, 7L);
        List<FilterStep> pipeline = List.of(new FilterStep("sharpen", Map.of("amount", 1.2)));
        Path ioDir = tempDir.resolve("io-work");

        List<BenchmarkResult> results = runner.runSuite(
                batch,
                pipeline,
                "sharpen",
                List.of(1, 2, 4),
                1,
                2,
                ioDir
        );

        // 1 sequential + 2 parallel engines * 3 thread counts = 7 rows
        assertEquals(7, results.size());
        BenchmarkResult seq = results.getFirst();
        assertEquals("sequential", seq.engineMode());
        assertEquals(1, seq.threads());
        assertEquals(1.0, seq.speedup(), 1e-9);
        assertEquals(1.0, seq.efficiency(), 1e-9);
        assertTrue(seq.correct());

        for (BenchmarkResult result : results) {
            assertEquals("sharpen", result.filterName());
            assertEquals(3, result.imageCount());
            assertEquals(32, result.width());
            assertEquals(32, result.height());
            assertTrue(result.computeMs() >= 0);
            assertTrue(result.totalMs() >= 0);
            assertTrue(result.speedup() > 0);
            assertTrue(result.efficiency() > 0);
            assertTrue(result.correct(), "Expected exact match for " + result.engineMode());
            assertEquals(0, result.maxDiff());
            assertEquals(0.0, result.mae());
        }

        Path csvFile = tempDir.resolve("out/benchmark.csv");
        runner.exportCsv(csvFile, results);
        assertTrue(Files.exists(csvFile));

        List<String> lines = Files.readAllLines(csvFile);
        assertEquals(8, lines.size());
        assertEquals(
                "engine,filter,images,width,height,threads,compute_ms,total_ms,speedup,efficiency,total_speedup,total_efficiency,correct,max_diff,mae",
                lines.getFirst()
        );

        String table = runner.formatTable(results);
        assertTrue(table.contains("sequential"));
        assertTrue(table.contains("threadpool"));
        assertTrue(table.contains("forkjoin"));
        assertTrue(table.contains("sharpen"));
    }

    @Test
    void validatesInputsAndBenchmarkResultConstraints() {
        List<ImageData> batch = BenchmarkRunner.generateSyntheticBatch(1, 8, 8, 1L);
        List<FilterStep> pipeline = List.of(new FilterStep("sharpen", Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkRunner.generateSyntheticBatch(0, 8, 8, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> runner.runSuite(List.of(), pipeline, "sharpen", List.of(1), 0, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> runner.runSuite(batch, List.of(), "sharpen", List.of(1), 0, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> runner.runSuite(batch, pipeline, "sharpen", List.of(), 0, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> runner.runSuite(batch, pipeline, "sharpen", List.of(0), 0, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> runner.runSuite(batch, pipeline, "sharpen", List.of(1), -1, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> runner.runSuite(batch, pipeline, "sharpen", List.of(1), 0, 0, null));

        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkResult("", "sharpen", 1, 8, 8, 1, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, true, 0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkResult("sequential", "sharpen", 1, 8, 8, 0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, true, 0, 0.0));
    }
}
