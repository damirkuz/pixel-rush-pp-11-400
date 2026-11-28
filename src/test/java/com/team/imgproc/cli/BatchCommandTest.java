package com.team.imgproc.cli;

import com.team.imgproc.engine.ThreadPoolEngine;
import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.MotionBlurFilter;
import com.team.imgproc.io.ImageFileIO;
import com.team.imgproc.model.ImageData;
import com.team.imgproc.pipeline.ImagePipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    private static BatchCommand command(ImageFileIO io) {
        var filters = new FilterRegistry(List.of(new MotionBlurFilter()));
        return new BatchCommand(new ImagePipeline(List.of(new ThreadPoolEngine(filters))), io,
                new MockEnvironment());
    }
}
