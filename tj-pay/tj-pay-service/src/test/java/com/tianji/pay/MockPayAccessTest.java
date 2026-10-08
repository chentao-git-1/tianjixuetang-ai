package com.tianji.pay;

import com.tianji.pay.third.mock.MockPayAccessFilter;
import com.tianji.pay.third.mock.MockPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MockPayAccessTest {
    private final String secret = "test-only-token-with-more-than-32-characters";

    @Test
    void allowsOnlyDirectLoopbackWithConfiguredToken() throws Exception {
        assertThat(invoke("127.0.0.1", secret, null, secret)).isEqualTo(200);
        assertThat(invoke("::1", secret, null, secret)).isEqualTo(200);
        assertThat(invoke("192.168.1.10", secret, null, secret)).isEqualTo(403);
        assertThat(invoke("127.0.0.1", null, null, secret)).isEqualTo(403);
        assertThat(invoke("127.0.0.1", "wrong", null, secret)).isEqualTo(403);
        assertThat(invoke("127.0.0.1", secret, "203.0.113.1", secret)).isEqualTo(403);
        assertThat(invoke("127.0.0.1", secret, null, "")).isEqualTo(403);
        assertThat(invoke("127.0.0.1", "short", null, "short")).isEqualTo(403);
    }

    private int invoke(String address, String supplied, String forwarded, String configured) throws Exception {
        MockPayProperties properties = new MockPayProperties();
        properties.setToken(configured);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mock-pay/1/success");
        request.setRemoteAddr(address);
        if (supplied != null) request.addHeader(MockPayAccessFilter.TOKEN_HEADER, supplied);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new MockPayAccessFilter(properties).doFilter(request, response, (req, res) -> called.set(true));
        assertThat(called.get()).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }
}
