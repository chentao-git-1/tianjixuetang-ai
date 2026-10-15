package com.tianji.aigc.controller;


import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.annotations.NoWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.Locale;



@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;


    @NoWrapper
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chat){
        try {
            return chatService.chat(chat)
                    .onErrorResume(error -> failedChat(chat.getSessionId(), error));
        } catch (Exception error) {
            // 建立流之前的同步异常也使用SSE返回，避免进入普通JSON异常处理。
            return failedChat(chat.getSessionId(), error);
        }
    }

    private Flux<ChatEventVO> failedChat(String sessionId, Throwable error) {
        log.error("对话流处理失败，sessionId = {}", sessionId, error);
        String message = "服务暂时不可用，请稍后重试。";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof JedisDataException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("no such index")) {
                message = "知识库索引尚未建立，请检查向量库配置并重启AI服务。";
                break;
            }
        }
        // 沿用现有DATA/STOP协议，前端可展示失败提示并结束加载，无需新增事件类型。
        return Flux.just(
                ChatEventVO.builder().eventType(ChatEventTypeEnum.DATA.getValue())
                        .eventData("\n\n[对话失败] " + message).build(),
                ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build());
    }

    @PostMapping("/stop")
    public void stopChat(String sessionId){
        chatService.stopChat(sessionId);
    }

}
