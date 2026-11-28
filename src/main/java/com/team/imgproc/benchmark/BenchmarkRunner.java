package com.team.imgproc.benchmark;

import com.team.imgproc.engine.ForkJoinEngine;
import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.engine.SequentialEngine;
import com.team.imgproc.engine.ThreadPoolEngine;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.GaussianBlurFilter;
import com.team.imgproc.filter.MedianFilter;
import com.team.imgproc.filter.MotionBlurFilter;
import com.team.imgproc.filter.SharpenFilter;
import com.team.imgproc.filter.SobelEdgeFilter;
import com.team.imgproc.io.ImageFileIO;
import com.team.imgproc.model.BenchmarkResult;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

@Component
public class BenchmarkRunner {
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final Map<String, ProcessingEngine> enginesByMode;
    private final ImageFileIO imageFileIO;
    private final CorrectnessVerifier verifier;

    public BenchmarkRunner(List<ProcessingEngine> engines,
                           ImageFileIO imageFileIO,
                           CorrectnessVerifier verifier) {
        Objects.requireNonNull(engines, "engines");
        this.imageFileIO = Objects.requireNonNull(imageFileIO, "imageFileIO");
        this.verifier = Objects.requireNonNull(verifier, "verifier");

        Map<String, ProcessingEngine> map = new LinkedHashMap<>();
        for (ProcessingEngine engine : engines) {
            map.put(engine.mode(), engine);
        }
        this.enginesByMode = Map.copyOf(map);
    }

    public static void main(String[] args) throws IOException {
        Path csvPath = Path.of(envOrDefault("PIXELRUSH_BENCH_CSV", "benchmark-results.csv"));
        List<Integer> threadCounts = parseThreadCounts(envOrDefault("PIXELRUSH_BENCH_THREADS", "1,2,4,8"));
        List<String> filterNames = parseFilterNames(envOrDefault("PIXELRUSH_BENCH_FILTERS", "gaussian,sobel,median,sharpen"));
        int count = Integer.parseInt(envOrDefault("PIXELRUSH_BENCH_COUNT", "8"));
        int width = Integer.parseInt(envOrDefault("PIXELRUSH_BENCH_WIDTH", "256"));
        int height = Integer.parseInt(envOrDefault("PIXELRUSH_BENCH_HEIGHT", "256"));
        int warmup = Integer.parseInt(envOrDefault("PIXELRUSH_BENCH_WARMUP", "2"));
        int iterations = Integer.parseInt(envOrDefault("PIXELRUSH_BENCH_ITERATIONS", "3"));

        FilterRegistry registry = new FilterRegistry(List.of(
                new GaussianBlurFilter(),
                new SobelEdgeFilter(),
                new MedianFilter(),
                new SharpenFilter(),
                new MotionBlurFilter()
        ));
        List<ProcessingEngine> engines = List.of(
                new SequentialEngine(registry),
                new ThreadPoolEngine(registry),
                new ForkJoinEngine(registry)
        );
        ImageFileIO io = new ImageFileIO();
        CorrectnessVerifier verifier = new CorrectnessVerifier();
        BenchmarkRunner runner = new BenchmarkRunner(engines, io, verifier);

        String inputDir = System.getenv("PIXELRUSH_INPUT");
        List<ImageData> batch;
        if (inputDir != null && !inputDir.isBlank()) {
            batch = io.readDirectory(Path.of(inputDir));
            if (batch.isEmpty()) {
                throw new IllegalArgumentException("No supported images found in " + inputDir);
            }
        } else {
            batch = generateSyntheticBatch(count, width, height, 42L);
        }

        Path tempIoDir = Files.createTempDirectory("pixel-rush-bench-");
        List<BenchmarkResult> allResults = new ArrayList<>();
        try {
            for (String filterName : filterNames) {
                List<FilterStep> pipeline = List.of(defaultStepFor(filterName));
                List<BenchmarkResult> results = runner.runSuite(
                        batch, pipeline, filterName, threadCounts, warmup, iterations, tempIoDir);
                allResults.addAll(results);
            }
        } finally {
            deleteDirectoryQuietly(tempIoDir);
        }

        runner.exportCsv(csvPath, allResults);
        System.out.println(runner.formatTable(allResults));
        System.out.printf("Benchmark CSV saved to %s%n", csvPath.toAbsolutePath());
    }

    public List<BenchmarkResult> runSuite(
            List<ImageData> batch,
            List<FilterStep> pipeline,
            String filterLabel,
            List<Integer> threadCounts,
            int warmupIterations,
            int measureIterations,
            Path ioWorkDir
    ) throws IOException {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(filterLabel, "filterLabel");
        Objects.requireNonNull(threadCounts, "threadCounts");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        if (pipeline.isEmpty()) {
            throw new IllegalArgumentException("pipeline must not be empty");
        }
        if (threadCounts.isEmpty()) {
            throw new IllegalArgumentException("threadCounts must not be empty");
        }
        if (warmupIterations < 0 || measureIterations < 1) {
            throw new IllegalArgumentException("Expected warmupIterations >= 0 and measureIterations >= 1");
        }

        ProcessingEngine sequential = enginesByMode.get("sequential");
        if (sequential == null) {
            throw new IllegalStateException("Sequential engine is required for baseline and verification");
        }

        int imageCount = batch.size();
        int width = batch.getFirst().width();
        int height = batch.getFirst().height();

        Path inputDir = null;
        Path outputDir = null;
        if (ioWorkDir != null) {
            inputDir = ioWorkDir.resolve("in");
            outputDir = ioWorkDir.resolve("out");
            imageFileIO.writeDirectory(inputDir, batch);
        }

        // 1. Warmup JVM for sequential engine
        for (int i = 0; i < warmupIterations; i++) {
            sequential.processBatch(batch, pipeline, 1);
        }

        // 2. Measure sequential baseline (Compute-only and End-to-End)
        List<ImageData> referenceOutput = null;
        long seqComputeNanos = 0;
        for (int i = 0; i < measureIterations; i++) {
            long start = System.nanoTime();
            List<ImageData> out = sequential.processBatch(batch, pipeline, 1);
            seqComputeNanos += System.nanoTime() - start;
            if (referenceOutput == null) {
                referenceOutput = out;
            }
        }
        double seqComputeMs = (seqComputeNanos / (double) measureIterations) / NANOS_PER_MILLISECOND;
        double seqTotalMs = ioWorkDir != null
                ? measureEndToEndMs(sequential, inputDir, outputDir, pipeline, 1, measureIterations)
                : seqComputeMs;

        List<BenchmarkResult> results = new ArrayList<>();
        results.add(new BenchmarkResult(
                sequential.mode(),
                filterLabel,
                imageCount,
                width,
                height,
                1,
                seqComputeMs,
                seqTotalMs,
                1.0,
                1.0,
                1.0,
                1.0,
                true,
                0,
                0.0
        ));

        // 3. Measure parallel engines across thread grid
        for (ProcessingEngine engine : enginesByMode.values()) {
            if (engine.mode().equals("sequential")) {
                continue;
            }
            for (int threads : threadCounts) {
                if (threads < 1) {
                    throw new IllegalArgumentException("threads must be positive");
                }

                for (int i = 0; i < warmupIterations; i++) {
                    engine.processBatch(batch, pipeline, threads);
                }

                List<ImageData> parallelOutput = null;
                long parComputeNanos = 0;
                for (int i = 0; i < measureIterations; i++) {
                    long start = System.nanoTime();
                    List<ImageData> out = engine.processBatch(batch, pipeline, threads);
                    parComputeNanos += System.nanoTime() - start;
                    if (parallelOutput == null) {
                        parallelOutput = out;
                    }
                }

                double parComputeMs = (parComputeNanos / (double) measureIterations) / NANOS_PER_MILLISECOND;
                double parTotalMs = ioWorkDir != null
                        ? measureEndToEndMs(engine, inputDir, outputDir, pipeline, threads, measureIterations)
                        : parComputeMs;

                CorrectnessVerifier.Report report = verifier.verifyBatch(referenceOutput, parallelOutput);

                double speedup = parComputeMs > 0 ? seqComputeMs / parComputeMs : 1.0;
                double efficiency = speedup / threads;
                double totalSpeedup = parTotalMs > 0 ? seqTotalMs / parTotalMs : 1.0;
                double totalEfficiency = totalSpeedup / threads;

                results.add(new BenchmarkResult(
                        engine.mode(),
                        filterLabel,
                        imageCount,
                        width,
                        height,
                        threads,
                        parComputeMs,
                        parTotalMs,
                        speedup,
                        efficiency,
                        totalSpeedup,
                        totalEfficiency,
                        report.exactMatch(),
                        report.maxDiff(),
                        report.mae()
                ));
            }
        }

        return List.copyOf(results);
    }

    private double measureEndToEndMs(
            ProcessingEngine engine,
            Path inputDir,
            Path outputDir,
            List<FilterStep> pipeline,
            int threads,
            int iterations
    ) throws IOException {
        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            List<ImageData> loaded = imageFileIO.readDirectory(inputDir);
            List<ImageData> processed = engine.processBatch(loaded, pipeline, threads);
            imageFileIO.writeDirectory(outputDir, processed);
            totalNanos += System.nanoTime() - start;
        }
        return (totalNanos / (double) iterations) / NANOS_PER_MILLISECOND;
    }

    public void exportCsv(Path csvFile, List<BenchmarkResult> results) throws IOException {
        Objects.requireNonNull(csvFile, "csvFile");
        Objects.requireNonNull(results, "results");

        Path parent = csvFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("engine,filter,images,width,height,threads,compute_ms,total_ms,speedup,efficiency,total_speedup,total_efficiency,correct,max_diff,mae\n");
        for (BenchmarkResult r : results) {
            sb.append(String.format(Locale.US,
                    "%s,%s,%d,%d,%d,%d,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%b,%d,%.6f%n",
                    r.engineMode(),
                    r.filterName(),
                    r.imageCount(),
                    r.width(),
                    r.height(),
                    r.threads(),
                    r.computeMs(),
                    r.totalMs(),
                    r.speedup(),
                    r.efficiency(),
                    r.totalSpeedup(),
                    r.totalEfficiency(),
                    r.correct(),
                    r.maxDiff(),
                    r.mae()));
        }
        Files.writeString(csvFile, sb.toString());
    }

    public String formatTable(List<BenchmarkResult> results) {
        Objects.requireNonNull(results, "results");
        StringBuilder sb = new StringBuilder();
        String header = String.format(Locale.US,
                "%-12s | %-10s | %6s | %9s | %7s | %10s | %10s | %8s | %10s | %7s | %8s%n",
                "Engine", "Filter", "Images", "Size", "Threads", "Compute ms", "Total ms", "Speedup", "Efficiency", "Correct", "MaxDiff");
        sb.append(header);
        sb.append("-".repeat(header.length() - 1)).append('\n');
        for (BenchmarkResult r : results) {
            sb.append(String.format(Locale.US,
                    "%-12s | %-10s | %6d | %4dx%-4d | %7d | %10.2f | %10.2f | %8.2f | %10.2f | %-7s | %8d%n",
                    r.engineMode(),
                    r.filterName(),
                    r.imageCount(),
                    r.width(),
                    r.height(),
                    r.threads(),
                    r.computeMs(),
                    r.totalMs(),
                    r.speedup(),
                    r.efficiency(),
                    r.correct() ? "OK" : "FAIL",
                    r.maxDiff()));
        }
        return sb.toString();
    }

    public static List<ImageData> generateSyntheticBatch(int imageCount, int width, int height, long seed) {
        if (imageCount < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Expected positive imageCount, width and height");
        }
        Random random = new Random(seed);
        List<ImageData> images = new ArrayList<>(imageCount);
        for (int i = 0; i < imageCount; i++) {
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = (x * 255 / Math.max(1, width - 1) + random.nextInt(32)) & 0xff;
                    int g = (y * 255 / Math.max(1, height - 1) + random.nextInt(32)) & 0xff;
                    int b = ((x + y) * 127 / Math.max(1, width + height - 2) + random.nextInt(32)) & 0xff;
                    pixels[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            String name = String.format(Locale.ROOT, "img-%03d.png", i + 1);
            images.add(new ImageData(name, width, height, pixels));
        }
        return List.copyOf(images);
    }

    private static FilterStep defaultStepFor(String filterName) {
        return switch (filterName) {
            case "gaussian" -> new FilterStep("gaussian", Map.of("radius", 2.0, "sigma", 1.2));
            case "sobel" -> new FilterStep("sobel", Map.of("scale", 1.0, "threshold", 10.0));
            case "median" -> new FilterStep("median", Map.of("radius", 1.0));
            case "sharpen" -> new FilterStep("sharpen", Map.of("amount", 1.0));
            default -> new FilterStep(filterName, Map.of());
        };
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static List<Integer> parseThreadCounts(String raw) {
        List<Integer> counts = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                counts.add(Integer.parseInt(trimmed));
            }
        }
        return List.copyOf(counts);
    }

    private static List<String> parseFilterNames(String raw) {
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return List.copyOf(names);
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
