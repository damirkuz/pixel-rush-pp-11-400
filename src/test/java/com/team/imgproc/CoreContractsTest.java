package com.team.imgproc;

import com.team.imgproc.filter.FilterRegistry;
import com.team.imgproc.filter.ImageFilter;
import com.team.imgproc.model.FilterStep;
import com.team.imgproc.model.ImageData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {PixelRushApplication.class, CoreContractsTest.TestFilters.class})
class CoreContractsTest {
    @Autowired
    private FilterRegistry filters;

    @Test
    void discoversFilterBeansByTheirDeclaredNames() {
        assertEquals("identity-test", filters.require("identity-test").name());
        assertThrows(IllegalArgumentException.class, () -> filters.require("unknown-test-filter"));
    }

    @Test
    void rejectsDuplicateFilterNames() {
        ImageFilter filter = filters.require("identity-test");
        assertThrows(IllegalArgumentException.class, () -> new FilterRegistry(List.of(filter, filter)));
    }

    @Test
    void imageOwnsItsPixelArray() {
        int[] pixels = {10, 20};
        ImageData image = new ImageData("example.png", 2, 1, pixels);
        pixels[0] = 99;
        image.pixels()[1] = 99;

        assertEquals(10, image.argbAt(0));
        assertArrayEquals(new int[]{10, 20}, image.pixels());
    }

    @Test
    void validatesImageDimensionsAndPixelCount() {
        assertThrows(IllegalArgumentException.class, () -> new ImageData("x.png", 0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new ImageData("x.png", 2, 1, new int[1]));
    }

    @Test
    void comparesEqualImagesByPixelValues() {
        ImageData image = new ImageData("example.png", 2, 1, new int[]{10, 20});
        ImageData copy = new ImageData(image.name(), image.width(), image.height(), image.pixels());

        assertEquals(image, copy);
        assertEquals(image.hashCode(), copy.hashCode());
    }

    @Test
    void stepOwnsItsParameters() {
        Map<String, Double> parameters = new HashMap<>(Map.of("radius", 2.0));
        FilterStep step = new FilterStep("gaussian", parameters);
        parameters.put("radius", 3.0);

        assertEquals(2.0, step.params().get("radius"));
        assertThrows(UnsupportedOperationException.class, () -> step.params().clear());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestFilters {
        @Bean
        ImageFilter identityFilter() {
            return new ImageFilter() {
                @Override
                public String name() {
                    return "identity-test";
                }

                @Override
                public void applyRegion(ImageData src, int[] dst, int startY, int endY,
                                        Map<String, Double> params) {
                    for (int index = startY * src.width(); index < endY * src.width(); index++) {
                        dst[index] = src.argbAt(index);
                    }
                }
            };
        }
    }
}
