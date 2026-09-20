package com.orderflow.catalog_pricing_service.api;

import java.io.IOException;

import com.orderflow.catalog_pricing_service.config.CatalogHttpProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(JsonMapper.builder().build(),
        new CatalogHttpProperties(DataSize.ofBytes(10)));

    @Test
    void passesBodyAtTheLimit() throws Exception {
        var request = post(new byte[10]);
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsKnownLengthAboveTheLimit() throws Exception {
        var request = post(new byte[11]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("/problems/payload-too-large")
            .contains("Request body must not exceed 10 bytes");
    }

    @Test
    void cutsOffUnknownLengthBodyAtTheLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/quotes") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[11]);
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertThatThrownBy(() -> ((HttpServletRequest) passed).getInputStream().readAllBytes())
            .isInstanceOf(IOException.class);
    }

    private static MockHttpServletRequest post(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/quotes");
        request.setContent(body);
        return request;
    }
}
