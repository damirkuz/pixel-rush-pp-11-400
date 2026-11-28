package com.team.imgproc.benchmark;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.io.ImageFileIO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

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

    @Test
    void detectsWrongOutputOnTheSecondMeasuredComputeIteration() throws IOException {
        var invocation = new AtomicInteger();
        var benchmark = withParallelEngine(images -> invocation.incrementAndGet() == 2
                ? corruptFirstPixel(images) : images);
        var batch = List.of(new ImageData("black.png", 4, 4, new int[16]));

        BenchmarkResult parallel = benchmark.runSuite(
                batch, sharpening(), "sharpen", List.of(1), 0, 2, null).getLast();

        assertFalse(parallel.correct());
        assertEquals(255, parallel.maxDiff());
        assertEquals(255 / 48.0, parallel.mae(), 1e-9);
    }

    @Test
    void detectsWrongOutputOnTheSecondMeasuredEndToEndIteration() throws IOException {
        var batch = List.of(new ImageData("black.png", 4, 4, new int[16]));
        var endToEndInvocation = new AtomicInteger();
        var benchmark = withParallelEngine(images -> images.getFirst() != batch.getFirst()
                && endToEndInvocation.incrementAndGet() == 2 ? corruptFirstPixel(images) : images);

        BenchmarkResult parallel = benchmark.runSuite(
                batch, sharpening(), "sharpen", List.of(1), 0, 2, tempDir).getLast();

        assertFalse(parallel.correct());
        assertEquals(255, parallel.maxDiff());
    }

    @Test
    void reusingWorkDirectoryMeasuresOnlyTheCurrentBatchAndCleansUp() throws IOException {
        var measuredSizes = new ArrayList<Integer>();
        var benchmark = withParallelEngine(images -> {
            measuredSizes.add(images.size());
            return images;
        });
        var batch = BenchmarkRunner.generateSyntheticBatch(2, 4, 4, 1);
        Files.writeString(tempDir.resolve("keep.txt"), "caller-owned");

        benchmark.runSuite(batch, sharpening(), "sharpen", List.of(1), 0, 1, tempDir);
        benchmark.runSuite(List.of(batch.getFirst()), sharpening(), "sharpen", List.of(1), 0, 1, tempDir);

        assertEquals(List.of(2, 2, 1, 1), measuredSizes);
        try (var children = Files.list(tempDir)) {
            assertEquals(List.of("keep.txt"), children.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void measuresOriginalJpegFilesWithoutReencodingTheInputs() throws IOException {
        Path inputs = Files.createDirectory(tempDir.resolve("original"));
        Path jpeg = inputs.resolve("source.jpg");
        var fixture = BenchmarkRunner.generateSyntheticBatch(1, 32, 32, 8).getFirst();
        var io = new ImageFileIO();
        io.write(jpeg, new ImageData(jpeg.getFileName().toString(), fixture.width(), fixture.height(), fixture.pixels()));
        byte[] originalBytes = Files.readAllBytes(jpeg);
        List<ImageData> batch = io.readDirectory(inputs);

        var pathsRead = new ArrayList<Path>();
        var trackingIO = new ImageFileIO() {
            @Override
            public List<ImageData> readDirectory(Path directory) throws IOException {
                pathsRead.add(directory);
                return super.readDirectory(directory);
            }
        };
        var benchmark = new BenchmarkRunner(
                List.of(engine("sequential", images -> images), engine("threadpool", images -> images)),
                trackingIO, new CorrectnessVerifier());
        var work = tempDir.resolve("work");
        List<BenchmarkResult> results = benchmark.runSuite(
                batch, sharpening(), "sharpen", List.of(1), 0, 2, work, inputs);

        assertEquals(List.of(inputs, inputs, inputs, inputs), pathsRead);
        assertArrayEquals(originalBytes, Files.readAllBytes(jpeg));
        assertTrue(results.stream().allMatch(BenchmarkResult::correct));
    }

    @Test
    void removesTemporaryFilesWhenAFullCycleFails() throws IOException {
        var batch = BenchmarkRunner.generateSyntheticBatch(1, 4, 4, 1);
        var benchmark = withParallelEngine(images -> {
            if (images.getFirst() != batch.getFirst()) {
                throw new IllegalStateException("Intentional end-to-end failure");
            }
            return images;
        });

        assertThrows(IllegalStateException.class, () -> benchmark.runSuite(
                batch, sharpening(), "sharpen", List.of(1), 0, 1, tempDir));
        try (var children = Files.list(tempDir)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void warmsUpEndToEndBeforeTakingMeasurements() throws IOException {
        var batch = BenchmarkRunner.generateSyntheticBatch(1, 4, 4, 1);
        var endToEndInvocation = new AtomicInteger();
        var benchmark = withParallelEngine(images -> {
            if (images.getFirst() != batch.getFirst()) {
                endToEndInvocation.incrementAndGet();
            }
            return images;
        });

        benchmark.runSuite(batch, sharpening(), "sharpen", List.of(1), 2, 1, tempDir);

        assertEquals(3, endToEndInvocation.get());
    }

    @Test
    void reportsMixedDimensionsInTheTableAndCsv() throws IOException {
        var batch = List.of(new ImageData("a.png", 4, 4, new int[16]),
                new ImageData("b.png", 8, 4, new int[32]));
        var benchmark = withParallelEngine(images -> images);

        List<BenchmarkResult> results = benchmark.runSuite(
                batch, sharpening(), "sharpen", List.of(1), 0, 1, null);
        Path csv = tempDir.resolve("mixed.csv");
        benchmark.exportCsv(csv, results);

        assertTrue(benchmark.formatTable(results).contains("mixed"));
        for (String row : Files.readAllLines(csv).subList(1, 3)) {
            String[] fields = row.split(",");
            assertEquals("2", fields[2]);
            assertEquals("0", fields[3]);
            assertEquals("0", fields[4]);
        }
    }

    @Test
    void commandRejectsAFilterListWithNoNames() throws IOException, InterruptedException {
        Path csv = tempDir.resolve("invalid.csv");
        Path log = tempDir.resolve("benchmark.log");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                BenchmarkRunner.class.getName());
        command.environment().put("PIXELRUSH_BENCH_FILTERS", ", ,");
        command.environment().put("PIXELRUSH_BENCH_CSV", csv.toString());
        command.environment().remove("PIXELRUSH_INPUT");
        var process = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Benchmark process timed out");
            String output = Files.readString(log);
            assertNotEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Expected at least one filter name"), output);
            assertFalse(Files.exists(csv));
        } finally {
            process.destroyForcibly();
        }
    }

    private static List<FilterStep> sharpening() {
        return List.of(new FilterStep("sharpen", Map.of()));
    }

    private static BenchmarkRunner withParallelEngine(UnaryOperator<List<ImageData>> process) {
        return new BenchmarkRunner(List.of(engine("sequential", images -> images), engine("threadpool", process)),
                new ImageFileIO(), new CorrectnessVerifier());
    }

    private static ProcessingEngine engine(String mode, UnaryOperator<List<ImageData>> process) {
        return new ProcessingEngine() {
            @Override
            public String mode() {
                return mode;
            }

            @Override
            public List<ImageData> processBatch(List<ImageData> images, List<FilterStep> pipeline, int threads) {
                return process.apply(images);
            }
        };
    }

    private static List<ImageData> corruptFirstPixel(List<ImageData> images) {
        var image = images.getFirst();
        int[] pixels = image.pixels();
        pixels[0] ^= 0xff;
        return List.of(new ImageData(image.name(), image.width(), image.height(), pixels));
    }
}
