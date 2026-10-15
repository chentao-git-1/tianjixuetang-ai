package com.tianji.common.autoconfigure.mvc.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.constants.Constant;
import com.tianji.common.domain.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WrapperResponseBodyAdviceTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new WrapperResponseBodyAdvice(mapper))
                // 字符串转换器优先，复现包装后R被强制转换为String的问题。
                .setMessageConverters(new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void gatewayStringKeepsJsonEnvelopeWithoutClassCast() throws Exception {
        mvc.perform(get("/string").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").value("hello"));
    }

    @Test
    void directStringResponseRemainsUnwrapped() throws Exception {
        mvc.perform(get("/string")).andExpect(status().isOk()).andExpect(content().string("hello"));
    }

    @Test
    void gatewayObjectAndExistingEnvelopeAreCompatible() throws Exception {
        mvc.perform(get("/object").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.value").value("hello"));
        mvc.perform(get("/wrapped").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.msg").value("failed"));
    }

    @Test
    void sseRemainsValidEvenWithoutNoWrapperAnnotation() throws Exception {
        mvc.perform(get("/events").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("data: hello\n\n"));
    }

    @Test
    void empty406DoesNotBecomeSuccessfulEnvelope() throws Exception {
        mvc.perform(get("/unacceptable").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(status().isNotAcceptable()).andExpect(content().string(""));
    }

    @Test
    void noWrapperAnnotationStillWorksForGateway() throws Exception {
        mvc.perform(get("/raw").header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME))
                .andExpect(content().string("raw"));
    }

    @RestController
    static class TestController {
        @GetMapping("/string") public String string() { return "hello"; }
        @GetMapping("/object") public Object object() { return Map.of("value", "hello"); }
        @GetMapping("/wrapped") public R<?> wrapped() { return R.error(400, "failed"); }
        @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public String events() { return "data: hello\n\n"; }
        @GetMapping("/unacceptable") public ResponseEntity<String> unacceptable() {
            return ResponseEntity.status(406).build();
        }
        @NoWrapper
        @GetMapping("/raw") public String raw() { return "raw"; }
    }
}
