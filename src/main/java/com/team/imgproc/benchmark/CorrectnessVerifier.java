package com.team.imgproc.benchmark;

import com.team.imgproc.model.ImageData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class CorrectnessVerifier {

    public record Report(
            boolean exactMatch,
            int totalPixels,
            int mismatchedPixels,
            int maxDiff,
            double mae
    ) {
        public boolean isWithinTolerance(int allowedMaxDiff, double allowedMae) {
            return maxDiff <= allowedMaxDiff && mae <= allowedMae;
        }
    }

    public Report verifyImage(ImageData expected, ImageData actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        validateMetadata(expected, actual);

        int pixelCount = expected.width() * expected.height();
        int mismatchedPixels = 0;
        int maxDiff = 0;
        long totalChannelDiff = 0;

        for (int i = 0; i < pixelCount; i++) {
            int expArgb = expected.argbAt(i);
            int actArgb = actual.argbAt(i);

            int diffA = Math.abs(((expArgb >>> 24) & 0xff) - ((actArgb >>> 24) & 0xff));
            int diffR = Math.abs(((expArgb >>> 16) & 0xff) - ((actArgb >>> 16) & 0xff));
            int diffG = Math.abs(((expArgb >>> 8) & 0xff) - ((actArgb >>> 8) & 0xff));
            int diffB = Math.abs((expArgb & 0xff) - (actArgb & 0xff));

            int pixelMax = Math.max(Math.max(diffA, diffR), Math.max(diffG, diffB));
            if (pixelMax > 0) {
                mismatchedPixels++;
                if (pixelMax > maxDiff) {
                    maxDiff = pixelMax;
                }
            }
            totalChannelDiff += diffR + diffG + diffB;
        }

        double mae = totalChannelDiff / (3.0 * pixelCount);
        return new Report(maxDiff == 0, pixelCount, mismatchedPixels, maxDiff, mae);
    }

    public Report verifyBatch(List<ImageData> expectedBatch, List<ImageData> actualBatch) {
        Objects.requireNonNull(expectedBatch, "expectedBatch");
        Objects.requireNonNull(actualBatch, "actualBatch");
        if (expectedBatch.size() != actualBatch.size()) {
            throw new IllegalArgumentException(
                    "Batch size mismatch: expected " + expectedBatch.size() + ", got " + actualBatch.size());
        }
        if (expectedBatch.isEmpty()) {
            return new Report(true, 0, 0, 0, 0.0);
        }

        int totalPixels = 0;
        int mismatchedPixels = 0;
        int maxDiff = 0;
        double weightedDiffSum = 0.0;

        for (int i = 0; i < expectedBatch.size(); i++) {
            Report imageReport = verifyImage(expectedBatch.get(i), actualBatch.get(i));
            totalPixels += imageReport.totalPixels();
            mismatchedPixels += imageReport.mismatchedPixels();
            if (imageReport.maxDiff() > maxDiff) {
                maxDiff = imageReport.maxDiff();
            }
            weightedDiffSum += imageReport.mae() * 3.0 * imageReport.totalPixels();
        }

        double mae = totalPixels == 0 ? 0.0 : weightedDiffSum / (3.0 * totalPixels);
        return new Report(maxDiff == 0, totalPixels, mismatchedPixels, maxDiff, mae);
    }

    private static void validateMetadata(ImageData expected, ImageData actual) {
        if (!expected.name().equals(actual.name())) {
            throw new IllegalArgumentException(
                    "Image name mismatch: expected " + expected.name() + ", got " + actual.name());
        }
        if (expected.width() != actual.width() || expected.height() != actual.height()) {
            throw new IllegalArgumentException(
                    "Image dimensions mismatch for " + expected.name() + ": expected "
                            + expected.width() + "x" + expected.height() + ", got "
                            + actual.width() + "x" + actual.height());
        }
    }
}
