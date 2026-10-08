package com.tianji.pay.third.mock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 在转发头处理之前校验实际连接地址，不接受反向代理转发的模拟请求。 */
@RequiredArgsConstructor
public class MockPayAccessFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Mock-Pay-Token";
    private final MockPayProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = request.getRemoteAddr();
        String token = request.getHeader(TOKEN_HEADER);
        String expected = properties.getToken();
        boolean loopback = "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
        boolean forwarded = request.getHeader("Forwarded") != null
                || request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Real-IP") != null;
        if (!loopback || forwarded || expected == null || expected.length() < 32 || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "模拟支付仅允许本机直连并携带有效开发令牌");
            return;
        }
        chain.doFilter(request, response);
    }
}
