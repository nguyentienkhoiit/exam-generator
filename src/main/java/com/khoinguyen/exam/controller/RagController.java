package com.khoinguyen.exam.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khoinguyen.exam.dto.ExamDraftRequest;
import com.khoinguyen.exam.dto.ExamDraftResponse;
import com.khoinguyen.exam.dto.ResponseData;
import com.khoinguyen.exam.service.RagApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagApplicationService ragApplicationService;

    public RagController(RagApplicationService ragApplicationService) {
        this.ragApplicationService = ragApplicationService;
    }

    @PostMapping(
            path = "/ask",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ResponseData<ExamDraftResponse>> ask(
            @RequestPart("prompt") String topic,
            @RequestPart("file") MultipartFile file
    ) throws JsonProcessingException {
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("topic is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getOriginalFilename() != null && !file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF uploads are supported");
        }

        ObjectMapper objectMapper = new ObjectMapper();
        ExamDraftRequest request = objectMapper.readValue(topic, ExamDraftRequest.class);

        ResponseData<ExamDraftResponse> response = ResponseData.<ExamDraftResponse>builder()
                .status(HttpStatus.CREATED.value())
                .message("Create user successfully")
                .data(ragApplicationService.ask(request, file))
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest req, Exception ex) {
        String path = req.getRequestURI();
        log.warn("event=api_error path={} msg={}", path, ex.getMessage(), ex);

        int status = 500;
        if (ex instanceof IllegalArgumentException) {
            status = 400;
        }

        return ResponseEntity.status(status).body(Map.of(
                "error", ex.getClass().getSimpleName(),
                "message", ex.getMessage(),
                "path", path
        ));
    }
}
