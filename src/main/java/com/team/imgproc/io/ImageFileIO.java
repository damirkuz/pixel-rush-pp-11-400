package com.team.imgproc.io;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ImageFileIO {
    public List<ImageData> readDirectory(Path directory) throws IOException {
        List<Path> files;
        try (var entries = Files.list(directory)) {
            files = entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> formatOf(path) != null)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }

        List<ImageData> images = new ArrayList<>(files.size());
        for (Path file : files) {
            images.add(read(file));
        }
        return List.copyOf(images);
    }

    public ImageData read(Path file) throws IOException {
        requireFormat(file);
        BufferedImage image;
        try (var input = Files.newInputStream(file)) {
            image = ImageIO.read(input);
        } catch (IOException error) {
            throw new IOException("Unable to read image: " + file, error);
        }
        if (image == null) {
            throw new IOException("Unable to decode image: " + file);
        }

        return toImageData(file.getFileName().toString(), image);
    }

    public ImageData readUpload(InputStream input, long maxPixels) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("Unable to decode image");
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpeg", "bmp").contains(format)) {
                    throw new IOException("Expected a PNG, JPEG or BMP image");
                }
                reader.setInput(stream, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > maxPixels) {
                    throw new IOException("Image exceeds " + maxPixels + " pixels");
                }
                return toImageData("upload.png", reader.read(0));
            } finally {
                reader.dispose();
            }
        }
    }

    public void writePng(OutputStream output, ImageData image) throws IOException {
        writeRaster(output, image, "png");
    }

    public void writeDirectory(Path directory, List<ImageData> images) throws IOException {
        Files.createDirectories(directory);
        Set<Object> writtenFiles = new HashSet<>();
        for (ImageData image : images) {
            Path name = directory.getFileSystem().getPath(image.name());
            if (name.isAbsolute() || name.getNameCount() != 1 || !name.toString().equals(image.name())) {
                throw new IllegalArgumentException("Image name must be a single file name: " + image.name());
            }
            Path destination = directory.resolve(name);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                    && writtenFiles.contains(fileIdentity(destination))) {
                throw new IOException("Duplicate output file in this batch: " + destination);
            }
            write(destination, image);
            writtenFiles.add(fileIdentity(destination));
        }
    }

    private static Object fileIdentity(Path file) throws IOException {
        // The same directory entry can have multiple spellings, depending on the file system.
        Object key = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        return key != null ? key : file.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    public void write(Path file, ImageData image) throws IOException {
        String format = requireFormat(file);
        Path destination = file.toAbsolutePath();
        Path temporary = Files.createTempFile(destination.getParent(), ".pixel-rush-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                writeRaster(output, image, format);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IOException("Unable to write image: " + file, error);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ImageData toImageData(String name, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        return new ImageData(name, width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }

    private static void writeRaster(OutputStream output, ImageData image, String format) throws IOException {
        int type = format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage raster = new BufferedImage(image.width(), image.height(), type);
        raster.setRGB(0, 0, image.width(), image.height(), image.pixels(), 0, image.width());
        if (!ImageIO.write(raster, format, output)) {
            throw new IOException("No ImageIO writer for " + format);
        }
    }

    private static String requireFormat(Path file) throws IOException {
        String format = formatOf(file);
        if (format == null) {
            throw new IOException("Unsupported image extension: " + file);
        }
        return format;
    }

    private static String formatOf(Path file) {
        Path basename = file.getFileName();
        if (basename == null) {
            return null;
        }
        String name = basename.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }

        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "png" -> "png";
            case "jpg", "jpeg" -> "jpeg";
            case "bmp" -> "bmp";
            default -> null;
        };
    }
}
