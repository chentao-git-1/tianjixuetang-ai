package com.tianji.aigc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.autoconfigure.mvc.advice.WrapperResponseBodyAdvice;
import com.tianji.common.constants.Constant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import redis.clients.jedis.exceptions.JedisDataException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
    private ChatService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(ChatService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setControllerAdvice(new CommonExceptionAdvice(), new WrapperResponseBodyAdvice(new ObjectMapper()))
                .build();
    }

    @Test
    void missingIndexIsReportedAsDataAndStopInSse() throws Exception {
        when(service.chat(any(ChatDTO.class))).thenReturn(Flux.error(new IllegalStateException(
                "Stream processing failed", new JedisDataException("default-index: no such index"))));
        String body = chat();
        assertThat(body).contains("data:", "对话失败", "知识库索引尚未建立", "\"eventType\":1001", "\"eventType\":1002")
                .doesNotContain("default-index", "JedisDataException", "\"code\"");
    }

    @Test
    void streamFailureAfterPartialOutputPreservesOutputAndEndsStream() throws Exception {
        when(service.chat(any(ChatDTO.class))).thenReturn(Flux.concat(
                Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.DATA.getValue()).eventData("partial answer").build()),
                Flux.error(new RuntimeException("private-upstream-detail"))));
        String body = chat();
        assertThat(body).contains("partial answer", "服务暂时不可用", "\"eventType\":1002")
                .doesNotContain("private-upstream-detail");
        assertThat(body.indexOf("partial answer")).isLessThan(body.indexOf("对话失败"));
    }

    @Test
    void synchronousFailureBeforeStreamCreationAlsoReturnsSse() throws Exception {
        when(service.chat(any(ChatDTO.class))).thenThrow(new IllegalStateException("configuration unavailable"));
        assertThat(chat()).contains("对话失败", "\"eventType\":1002").doesNotContain("configuration unavailable");
    }

    private String chat() throws Exception {
        MvcResult result = mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME)
                        .content("{\"sessionId\":\"test-session\",\"question\":\"test\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
