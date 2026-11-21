package com.team.imgproc.io;

import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageFileIOTest {
    @TempDir
    Path directory;

    private final ImageFileIO io = new ImageFileIO();

    @Test
    void roundTripsPngAndBmpAndIgnoresUnsupportedNames() throws IOException {
        ImageData png = new ImageData("b.PNG", 2, 1, new int[]{0x77334455, 0xff9900ff});
        ImageData bmp = new ImageData("a.bmp", 2, 1, new int[]{0xff335577, 0xff99ffee});
        Path output = directory.resolve("images");
        io.writeDirectory(output, List.of(png, bmp));
        Files.createDirectory(output.resolve("directory.png"));
        Files.writeString(output.resolve("notes.txt"), "ignored");

        assertEquals(List.of(bmp, png), io.readDirectory(output));
    }

    @Test
    void writesJpegFromArgbAndKeepsColorWithinCompressionTolerance() throws IOException {
        int[] pixels = new int[64];
        java.util.Arrays.fill(pixels, 0x56336699);
        Path output = directory.resolve("color.JpEg");

        io.write(output, new ImageData("color.JpEg", 8, 8, pixels));
        ImageData decoded = io.read(output);

        assertEquals(8, decoded.width());
        assertEquals(8, decoded.height());
        for (int argb : decoded.pixels()) {
            assertEquals(0xff, argb >>> 24);
            assertEquals(0x33, (argb >>> 16) & 0xff, 5);
            assertEquals(0x66, (argb >>> 8) & 0xff, 5);
            assertEquals(0x99, argb & 0xff, 5);
        }
    }

    @Test
    void reportsUndecodableFileByPath() throws IOException {
        Path broken = Files.writeString(directory.resolve("broken.png"), "invalid PNG");
        IOException error = assertThrows(IOException.class, () -> io.readDirectory(directory));

        assertTrue(error.getMessage().contains(broken.toString()));
    }

    @Test
    void rejectsPathsAsBatchOutputNames() {
        for (String name : List.of("../escape.png", directory.resolve("escape.png").toString(),
                                   "bad\0name.png")) {
            ImageData image = new ImageData(name, 1, 1, new int[1]);
            assertThrows(IllegalArgumentException.class, () -> io.writeDirectory(directory, List.of(image)));
        }
        assertFalse(Files.exists(directory.resolve("escape.png")));
    }

    @Test
    void preservesExistingTargetWhenReplacementFails() throws IOException {
        Path output = Files.createDirectory(directory.resolve("output.png"));
        Path existing = Files.writeString(output.resolve("keep.txt"), "original");
        ImageData image = new ImageData("output.png", 1, 1, new int[]{0xffabcdef});

        assertThrows(IOException.class, () -> io.write(output, image));
        assertEquals("original", Files.readString(existing));
        try (var contents = Files.list(directory)) {
            assertEquals(List.of(output), contents.toList());
        }
    }

    @Test
    void readsEmptyBatch() throws IOException {
        assertTrue(io.readDirectory(directory).isEmpty());
    }
}
