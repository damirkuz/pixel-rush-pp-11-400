package com.team.imgproc.demo;

import com.team.imgproc.PixelRushApplication;
import com.team.imgproc.engine.ProcessingEngine;
import com.team.imgproc.io.ImageFileIO;
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
class SequentialDemoTest {
    @TempDir
    Path directory;

    @Autowired
    private ImageFileIO io;

    @Autowired
    private List<ProcessingEngine> engines;

    @Test
    void processesFilesUsingTheSpringRegisteredEngineAndFilter() throws IOException {
        Path input = directory.resolve("in");
        Path output = directory.resolve("out");
        var image = new ImageData("contrast.png", 3, 1, new int[]{0xff000000, 0xffffffff, 0xff000000});
        io.writeDirectory(input, List.of(image));

        int count = SequentialDemo.run(input, output, Map.of("radius", 1.0, "sigma", 1.0), io, sequential());

        assertEquals(1, count);
        assertEquals(image, io.read(input.resolve(image.name())));
        assertArrayEquals(new int[]{0xff464646, 0xff737373, 0xff464646},
                io.read(output.resolve(image.name())).pixels());
    }

    @Test
    void rejectsSameDirectoryThroughAnAlias() throws IOException {
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), directory);

        assertThrows(IOException.class, () -> SequentialDemo.run(directory, alias, Map.of(), io, sequential()));
    }

    @Test
    void skipsInputSymlinksToProtectFilesInTheOutputDirectory() throws IOException {
        Path input = Files.createDirectory(directory.resolve("in"));
        Path output = directory.resolve("out");
        var image = new ImageData("contrast.png", 3, 1, new int[]{0xff000000, 0xffffffff, 0xff000000});
        io.writeDirectory(output, List.of(image));
        Path target = output.resolve(image.name());
        Files.createSymbolicLink(input.resolve(image.name()), target);

        assertEquals(0, SequentialDemo.run(input, output, Map.of(), io, sequential()));
        assertEquals(image, io.read(target));
    }

    private ProcessingEngine sequential() {
        return engines.stream().filter(engine -> engine.mode().equals("sequential")).findFirst().orElseThrow();
    }
}
