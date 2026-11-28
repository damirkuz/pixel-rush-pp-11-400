package com.team.imgproc.io;

import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    void normalizesEveryExifOrientationForBatchAndUploads() throws IOException {
        byte[] jpeg = encodeTestJpeg();
        int[] raw = io.readUpload(new ByteArrayInputStream(jpeg), 6).pixels();
        int[][] sourceIndicesByOrientation = {
                {0, 1, 2, 3, 4, 5},
                {2, 1, 0, 5, 4, 3},
                {5, 4, 3, 2, 1, 0},
                {3, 4, 5, 0, 1, 2},
                {0, 3, 1, 4, 2, 5},
                {3, 0, 4, 1, 5, 2},
                {5, 2, 4, 1, 3, 0},
                {2, 5, 1, 4, 0, 3}
        };

        for (int orientation = 1; orientation <= 8; orientation++) {
            ByteOrder order = orientation % 2 == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            byte[] oriented = withExifOrientation(jpeg, orientation, order);
            String name = "orientation-" + orientation + ".jpg";
            Path file = Files.write(directory.resolve(name), oriented);
            int width = orientation >= 5 ? 2 : 3;
            int height = orientation >= 5 ? 3 : 2;
            int[] expected = java.util.Arrays.stream(sourceIndicesByOrientation[orientation - 1])
                    .map(index -> raw[index]).toArray();

            assertEquals(new ImageData(name, width, height, expected), io.read(file));
            assertEquals(new ImageData("upload.png", width, height, expected),
                    io.readUpload(new ByteArrayInputStream(oriented), 6));
        }
    }

    @Test
    void rejectsUnsupportedContentEvenWhenNamedAsAPng() throws IOException {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "gif", bytes));
        Path file = Files.write(directory.resolve("incorrect.png"), bytes.toByteArray());

        assertThrows(IOException.class, () -> io.read(file));
        assertThrows(IOException.class, () -> io.readUpload(new ByteArrayInputStream(bytes.toByteArray()), 1));
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

    @Test
    void rejectsDuplicateOutputWithoutReplacingTheFirstResult() throws IOException {
        ImageData first = new ImageData("first.png", 1, 1, new int[]{0xff123456});
        ImageData duplicate = new ImageData("first.png", 1, 1, new int[]{0xffabcdef});

        assertThrows(IOException.class, () -> io.writeDirectory(directory, List.of(first, duplicate)));
        assertEquals(first, io.read(directory.resolve(first.name())));
    }

    @Test
    void detectsCaseCollisionsAccordingToTheOutputFileSystem() throws IOException {
        Files.createFile(directory.resolve("case-probe"));
        boolean caseInsensitive = Files.exists(directory.resolve("CASE-PROBE"));
        ImageData lower = new ImageData("image.png", 1, 1, new int[]{0xff123456});
        ImageData upper = new ImageData("IMAGE.PNG", 1, 1, new int[]{0xffabcdef});

        if (caseInsensitive) {
            assertThrows(IOException.class, () -> io.writeDirectory(directory, List.of(lower, upper)));
        } else {
            io.writeDirectory(directory, List.of(lower, upper));
            assertEquals(upper, io.read(directory.resolve(upper.name())));
        }
        assertEquals(lower, io.read(directory.resolve(lower.name())));
    }

    private static byte[] encodeTestJpeg() throws IOException {
        var raster = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        raster.setRGB(0, 0, 3, 2,
                new int[]{0xff000000, 0xff333333, 0xff666666, 0xff999999, 0xffcccccc, 0xffffffff}, 0, 3);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(raster, "jpeg", bytes));
        return bytes.toByteArray();
    }

    private static byte[] withExifOrientation(byte[] jpeg, int orientation, ByteOrder order) {
        // The generated JPEG starts with its JFIF APP0 segment. Insert EXIF immediately after it.
        int markerOffset = 4 + Short.toUnsignedInt(ByteBuffer.wrap(jpeg).getShort(4));
        var result = ByteBuffer.allocate(jpeg.length + 36);
        result.put(jpeg, 0, markerOffset);
        result.putShort((short) 0xffe1).putShort((short) 34);
        result.put(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        result.putShort(order == ByteOrder.LITTLE_ENDIAN ? (short) 0x4949 : (short) 0x4d4d);
        result.order(order).putShort((short) 42).putInt(8);
        result.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1);
        result.putShort((short) orientation).putShort((short) 0).putInt(0);
        result.put(jpeg, markerOffset, jpeg.length - markerOffset);
        return result.array();
    }
}
