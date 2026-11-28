package com.team.imgproc;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PixelRushApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(PixelRushApplication.class);
        if (new DefaultApplicationArguments(args).containsOption("batch")) {
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run(args)) {
                // All batch work runs before SpringApplication.run returns.
            }
        } else {
            application.run(args);
        }
    }
}
