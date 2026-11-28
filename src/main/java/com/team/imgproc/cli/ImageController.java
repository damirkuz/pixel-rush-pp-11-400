package com.team.imgproc.cli;

import com.team.imgproc.io.ImageFileIO;
import com.team.imgproc.model.ImageData;
import com.team.imgproc.pipeline.ImagePipeline;
import com.team.imgproc.pipeline.PipelineSpec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@RestController
public class ImageController {
    private static final long MAX_PIXELS = 16_777_216;

    private final ImagePipeline pipeline;
    private final ImageFileIO io;

    public ImageController(ImagePipeline pipeline, ImageFileIO io) {
        this.pipeline = pipeline;
        this.io = io;
    }

    @Operation(summary = "Apply a filter pipeline to an uploaded image and download a PNG")
    @ApiResponse(responseCode = "200", description = "Filtered PNG",
            content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "400", description = "Invalid image or processing parameters", content = @Content)
    @PostMapping(value = "/api/images/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> process(
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Ordered filters with optional key=value parameters",
                    example = "median:radius=1,motion:radius=3:angle=45")
            @RequestParam(defaultValue = "gaussian") String pipeline,
            @Parameter(description = "sequential, threadpool or forkjoin")
            @RequestParam(defaultValue = "sequential") String mode,
            @RequestParam(defaultValue = "1") int threads) throws IOException {
        ImageData input;
        try (var bytes = file.getInputStream()) {
            input = io.readUpload(bytes, MAX_PIXELS);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        ImageData result;
        try {
            var steps = PipelineSpec.parse(pipeline);
            result = this.pipeline.process(List.of(input), steps, mode, threads).getFirst();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        var output = new ByteArrayOutputStream();
        io.writePng(output, result);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"processed.png\"")
                .body(output.toByteArray());
    }
}
