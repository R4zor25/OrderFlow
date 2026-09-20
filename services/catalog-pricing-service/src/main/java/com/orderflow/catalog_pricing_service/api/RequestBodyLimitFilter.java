package com.orderflow.catalog_pricing_service.api;

import java.io.IOException;
import java.util.LinkedHashMap;

import com.orderflow.catalog_pricing_service.config.CatalogHttpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

// Runs before Spring Security so an oversized body is refused without parsing or authenticating it.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper json;
    private final long limit;

    public RequestBodyLimitFilter(ObjectMapper json, CatalogHttpProperties properties) {
        this.json = json;
        this.limit = properties.maxRequestBodySize().toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length > limit) {
            reject(request, response);
            return;
        }
        chain.doFilter(length < 0 ? new LimitedRequest(request, limit) : request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "/problems/payload-too-large");
        body.put("title", "Payload Too Large");
        body.put("status", HttpStatus.CONTENT_TOO_LARGE.value());
        body.put("detail", "Request body must not exceed " + limit + " bytes");
        body.put("instance", request.getRequestURI());
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream body = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int next = body.read();
                    if (next != -1) {
                        count(1);
                    }
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = body.read(buffer, offset, length);
                    if (count > 0) {
                        count(count);
                    }
                    return count;
                }

                private void count(int bytes) throws IOException {
                    read += bytes;
                    if (read > limit) {
                        throw new IOException("Request body exceeds the configured limit");
                    }
                }

                @Override
                public boolean isFinished() {
                    return body.isFinished();
                }

                @Override
                public boolean isReady() {
                    return body.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    body.setReadListener(listener);
                }
            };
        }
    }
}
