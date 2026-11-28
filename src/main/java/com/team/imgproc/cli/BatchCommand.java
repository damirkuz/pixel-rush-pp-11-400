package com.team.imgproc.cli;

import com.team.imgproc.io.ImageFileIO;
import com.team.imgproc.pipeline.ImagePipeline;
import com.team.imgproc.pipeline.PipelineSpec;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Component
public class BatchCommand implements ApplicationRunner {
    private final ImagePipeline pipeline;
    private final ImageFileIO io;
    private final Environment environment;

    public BatchCommand(ImagePipeline pipeline, ImageFileIO io, Environment environment) {
        this.pipeline = pipeline;
        this.io = io;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption("batch")) {
            return;
        }
        Path input = requiredPath(args, "input");
        Path output = requiredPath(args, "output");
        if (Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IOException("Input and output directories must be different: " + input);
        }

        String mode = option(args, "mode", "sequential");
        int threads = Integer.parseInt(option(args, "threads", "1"));
        var steps = PipelineSpec.parse(option(args, "pipeline", "gaussian"));
        var results = pipeline.process(io.readDirectory(input), steps, mode, threads);
        io.writeDirectory(output, results);
        System.out.printf("Processed %d image(s) into %s%n", results.size(), output.toAbsolutePath());
    }

    private Path requiredPath(ApplicationArguments args, String name) {
        String value = option(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException("Specify --" + name + "=<directory>");
        }
        return Path.of(value);
    }

    private String option(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values != null) {
            if (values.size() != 1 || values.getFirst().isBlank()) {
                throw new IllegalArgumentException("Specify exactly one value as --" + name + "=<value>");
            }
            return values.getFirst();
        }
        String value = environment.getProperty("PIXELRUSH_" + name.toUpperCase(Locale.ROOT));
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
