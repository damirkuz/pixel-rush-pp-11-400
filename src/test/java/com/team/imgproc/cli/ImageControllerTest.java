package com.team.imgproc.cli;

import com.team.imgproc.PixelRushApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = PixelRushApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImageControllerTest {
    @LocalServerPort
    private int port;

    @Test
    void acceptsMultipartImageAndReturnsFilteredPng() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = upload(client, "motion:radius=1");

            assertEquals(200, response.statusCode());
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
            var png = ImageIO.read(new ByteArrayInputStream(response.body()));
            assertEquals(0xff555555, png.getRGB(1, 0));
        }
    }

    @Test
    void reportsInvalidFiltersAsBadRequests() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(400, upload(client, "unregistered").statusCode());
            assertEquals(400, upload(client, "motion:radius=-1").statusCode());
            assertEquals(400, upload(client, "motion:raduis=24").statusCode());
        }
    }

    @Test
    void exposesSwaggerAndTheProcessingRoute() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var docs = client.send(HttpRequest.newBuilder(uri("/v3/api-docs")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, docs.statusCode());
            var operation = JsonMapper.builder().build().readTree(docs.body())
                    .at("/paths/~1api~1images~1process/post");
            assertEquals("binary", operation.at(
                    "/requestBody/content/multipart~1form-data/schema/properties/file/format").asString());
            assertEquals("binary", operation.at("/responses/200/content/image~1png/schema/format").asString());

            var ui = client.send(HttpRequest.newBuilder(uri("/swagger-ui/index.html")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ui.statusCode());
            assertTrue(ui.body().contains("Swagger UI"));
        }
    }

    private HttpResponse<byte[]> upload(HttpClient client, String pipeline) throws IOException, InterruptedException {
        String boundary = "pixel-rush-test";
        var body = new ByteArrayOutputStream();
        var image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 0, 0xffffffff);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"line.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        ImageIO.write(image, "png", body);
        body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"pipeline\"\r\n\r\n"
                + pipeline + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        var request = HttpRequest.newBuilder(uri("/api/images/process"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
