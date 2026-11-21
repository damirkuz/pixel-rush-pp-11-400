package com.team.imgproc.io;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class ImageFileIO {
    public List<ImageData> readDirectory(Path directory) throws IOException {
        List<Path> files;
        try (var entries = Files.list(directory)) {
            files = entries.filter(Files::isRegularFile)
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

        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new ImageData(file.getFileName().toString(), width, height, pixels);
    }

    public void writeDirectory(Path directory, List<ImageData> images) throws IOException {
        Files.createDirectories(directory);
        for (ImageData image : images) {
            Path name = directory.getFileSystem().getPath(image.name());
            if (name.isAbsolute() || name.getNameCount() != 1 || !name.toString().equals(image.name())) {
                throw new IllegalArgumentException("Image name must be a single file name: " + image.name());
            }
            write(directory.resolve(name), image);
        }
    }

    public void write(Path file, ImageData image) throws IOException {
        String format = requireFormat(file);
        int type = format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage raster = new BufferedImage(image.width(), image.height(), type);
        raster.setRGB(0, 0, image.width(), image.height(), image.pixels(), 0, image.width());

        Path destination = file.toAbsolutePath();
        Path temporary = Files.createTempFile(destination.getParent(), ".pixel-rush-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                if (!ImageIO.write(raster, format, output)) {
                    throw new IOException("No ImageIO writer for " + format);
                }
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
