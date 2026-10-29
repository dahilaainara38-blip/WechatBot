package com.demo.WechatBot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class SecurityResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeError(HttpServletResponse response, int status, String message) throws IOException {
        write(response, status, Map.of("error", message));
    }

    public void writeOk(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_OK, Map.of("ok", true));
    }

    private void write(HttpServletResponse response, int status, Map<String, ?> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
