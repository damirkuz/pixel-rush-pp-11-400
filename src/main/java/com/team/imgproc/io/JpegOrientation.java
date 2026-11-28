package com.team.imgproc.io;

import com.team.imgproc.model.ImageData;

import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class JpegOrientation {
    private static final String JPEG_FORMAT = "javax_imageio_jpeg_image_1.0";
    private static final int EXIF_SIGNATURE_BYTES = 6;
    private static final int TIFF_HEADER_BYTES = 8;
    private static final int IFD_ENTRY_BYTES = 12;

    private JpegOrientation() {}

    static int read(ImageReader reader) {
        try {
            var metadata = reader.getImageMetadata(0);
            if (metadata != null && JPEG_FORMAT.equals(metadata.getNativeMetadataFormatName())
                    && metadata.getAsTree(JPEG_FORMAT) instanceof IIOMetadataNode root) {
                var nodes = root.getElementsByTagName("unknown");
                for (int index = 0; index < nodes.getLength(); index++) {
                    var node = (IIOMetadataNode) nodes.item(index);
                    if (node.getAttribute("MarkerTag").equals("225") && node.getUserObject() instanceof byte[] bytes) {
                        int orientation = parseExif(bytes);
                        if (orientation != 0) {
                            return orientation;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Optional metadata may be unreadable even when the pixel data can be decoded.
        }
        return 1;
    }

    private static int parseExif(byte[] bytes) {
        if (bytes.length < EXIF_SIGNATURE_BYTES + TIFF_HEADER_BYTES + Short.BYTES) {
            return 0;
        }
        var app1 = ByteBuffer.wrap(bytes);
        if (app1.getInt() != 0x45786966 || app1.getShort() != 0) {
            return 0;
        }

        var tiff = app1.slice();
        switch (tiff.getShort()) {
            case 0x4949 -> tiff.order(ByteOrder.LITTLE_ENDIAN);
            case 0x4d4d -> tiff.order(ByteOrder.BIG_ENDIAN);
            default -> { return 0; }
        }
        if (tiff.getShort() != 42) {
            return 0;
        }
        long firstDirectory = Integer.toUnsignedLong(tiff.getInt());
        if (firstDirectory < TIFF_HEADER_BYTES || firstDirectory > tiff.limit() - Short.BYTES) {
            return 0;
        }
        tiff.position((int) firstDirectory);
        int entries = Short.toUnsignedInt(tiff.getShort());
        if (entries > tiff.remaining() / IFD_ENTRY_BYTES) {
            return 0;
        }
        for (int index = 0; index < entries; index++) {
            int offset = tiff.position() + index * IFD_ENTRY_BYTES;
            if (tiff.getShort(offset) == 0x0112 && tiff.getShort(offset + 2) == 3
                    && tiff.getInt(offset + 4) == 1) {
                int orientation = tiff.getShort(offset + 8);
                return orientation >= 1 && orientation <= 8 ? orientation : 0;
            }
        }
        return 0;
    }

    static ImageData toImageData(String name, BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] original = image.getRGB(0, 0, width, height, null, 0, width);
        if (orientation == 1) {
            return new ImageData(name, width, height, original);
        }

        int outputWidth = orientation >= 5 ? height : width;
        int outputHeight = orientation >= 5 ? width : height;
        int[] transformed = new int[original.length];
        for (int y = 0; y < outputHeight; y++) {
            for (int x = 0; x < outputWidth; x++) {
                int source = switch (orientation) {
                    case 2 -> y * width + (width - 1 - x);
                    case 3 -> (height - 1 - y) * width + (width - 1 - x);
                    case 4 -> (height - 1 - y) * width + x;
                    case 5 -> x * width + y;
                    case 6 -> (height - 1 - x) * width + y;
                    case 7 -> (height - 1 - x) * width + (width - 1 - y);
                    case 8 -> x * width + (width - 1 - y);
                    default -> throw new IllegalArgumentException("Invalid EXIF orientation: " + orientation);
                };
                transformed[y * outputWidth + x] = original[source];
            }
        }
        return new ImageData(name, outputWidth, outputHeight, transformed);
    }
}
