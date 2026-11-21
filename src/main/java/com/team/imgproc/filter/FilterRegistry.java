package com.team.imgproc.filter;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FilterRegistry {
    private final Map<String, ImageFilter> filtersByName;

    public FilterRegistry(List<ImageFilter> filters) {
        Map<String, ImageFilter> byName = new HashMap<>();
        for (ImageFilter filter : filters) {
            String name = filter.name();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Filter name must be non-blank");
            }
            if (byName.putIfAbsent(name, filter) != null) {
                throw new IllegalArgumentException("Duplicate filter name: " + name);
            }
        }
        filtersByName = Map.copyOf(byName);
    }

    public ImageFilter require(String name) {
        ImageFilter filter = filtersByName.get(name);
        if (filter == null) {
            throw new IllegalArgumentException("Unknown filter: " + name);
        }
        return filter;
    }
}
