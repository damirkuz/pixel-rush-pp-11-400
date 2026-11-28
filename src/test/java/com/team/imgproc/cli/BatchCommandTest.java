package com.team.imgproc.cli;

import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.engine.SequentialEngine;
import com.team.imgproc.engine.ThreadPoolEngine;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.GaussianBlurFilter;
import com.team.imgproc.filter.MotionBlurFilter;
import com.team.imgproc.io.ImageFileIO;
import com.team.imgproc.model.ImageData;
import com.team.imgproc.pipeline.ImagePipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchCommandTest {
    @TempDir
    Path directory;

    @Test
    void processesAFileFromCommandLineOptions() throws IOException {
        var io = new ImageFileIO();
        Path input = directory.resolve("source files");
        Path output = directory.resolve("processed files");
        var image = new ImageData("line.png", 3, 1, new int[]{0xff000000, 0xffffffff, 0xff000000});
        io.writeDirectory(input, List.of(image));

        var command = command(io);
        command.run(new DefaultApplicationArguments("--batch", "--input=" + input, "--output=" + output,
                "--mode=threadpool", "--threads=2", "--pipeline=motion:radius=1"));

        assertArrayEquals(new int[]{0xff555555, 0xff555555, 0xff555555},
                io.read(output.resolve("line.png")).pixels());
    }

    @Test
    void refusesToReplaceSourceImages() throws IOException {
        var io = new ImageFileIO();
        var image = new ImageData("line.png", 1, 1, new int[]{0xff000000});
        io.writeDirectory(directory, List.of(image));
        var args = new DefaultApplicationArguments("--batch", "--input=" + directory,
                "--output=" + directory.resolve("."), "--mode=threadpool", "--pipeline=motion");

        assertThrows(IOException.class, () -> command(io).run(args));
        assertArrayEquals(image.pixels(), io.read(directory.resolve("line.png")).pixels());
    }

    @Test
    void acceptsTheSequentialMakeTargetsEnvironment() throws IOException {
        var io = new ImageFileIO();
        Path input = directory.resolve("in");
        Path output = directory.resolve("out");
        var image = new ImageData("contrast.png", 3, 1, new int[]{0xff000000, 0xffffffff, 0xff000000});
        io.writeDirectory(input, List.of(image));
        var env = new MockEnvironment()
                .withProperty("PIXELRUSH_INPUT", input.toString())
                .withProperty("PIXELRUSH_OUTPUT", output.toString())
                .withProperty("PIXELRUSH_MODE", "sequential")
                .withProperty("PIXELRUSH_RADIUS", "1")
                .withProperty("PIXELRUSH_SIGMA", "1");

        command(io, env).run(new DefaultApplicationArguments("--batch"));

        assertEquals(image, io.read(input.resolve(image.name())));
        assertArrayEquals(new int[]{0xff464646, 0xff737373, 0xff464646},
                io.read(output.resolve(image.name())).pixels());
    }

    @Test
    void rejectsTheInputDirectoryThroughASymbolicLink() throws IOException {
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), directory);
        var args = new DefaultApplicationArguments("--batch", "--input=" + directory, "--output=" + alias);

        assertThrows(IOException.class, () -> command(new ImageFileIO()).run(args));
    }

    @Test
    void skipsInputSymlinksThatPointIntoTheOutputDirectory() throws IOException {
        var io = new ImageFileIO();
        Path input = Files.createDirectory(directory.resolve("in"));
        Path output = directory.resolve("out");
        var image = new ImageData("line.png", 3, 1, new int[]{0xff000000, 0xffffffff, 0xff000000});
        io.writeDirectory(output, List.of(image));
        Path target = output.resolve(image.name());
        Files.createSymbolicLink(input.resolve(image.name()), target);

        command(io).run(new DefaultApplicationArguments("--batch", "--input=" + input, "--output=" + output));

        assertEquals(image, io.read(target));
    }

    private static BatchCommand command(ImageFileIO io) {
        return command(io, new MockEnvironment());
    }

    private static BatchCommand command(ImageFileIO io, MockEnvironment env) {
        var filters = new FilterRegistry(List.of(new MotionBlurFilter(), new GaussianBlurFilter()));
        List<ProcessingEngine> engines = List.of(new SequentialEngine(filters), new ThreadPoolEngine(filters));
        return new BatchCommand(new ImagePipeline(engines, filters), io, env);
    }
}
