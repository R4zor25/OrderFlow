package com.orderflow.order.platform.web;

import java.io.IOException;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper json;

    public SecurityProblemHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        // The bearer entry point owns the RFC 6750 WWW-Authenticate header; the body follows the API's ProblemDetail format.
        bearerEntryPoint.commence(request, response, exception);
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
            "A valid bearer token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        bearerDeniedHandler.handle(request, response, exception);
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
            "The token does not grant access to this resource");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String kind, String title, String detail) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "/problems/" + kind);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
