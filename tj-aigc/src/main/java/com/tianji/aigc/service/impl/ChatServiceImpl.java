package com.tianji.aigc.service.impl;


import cn.hutool.core.date.DateUtil;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    private final SystemPromptConfig systemPromptConfig;

//    private static final Map<String,Boolean> GENERATE_STATUS=new ConcurrentHashMap<>();

    private final  StringRedisTemplate stringRedisTemplate;


    @Override
    public Flux<ChatEventVO> chat(ChatDTO chat) {



        return this.chatClient.prompt()
                .system(promptSystem-> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get())
                        .params(Map.of("now", DateUtil.now()))
                )
                .user(chat.getQuestion())
                .stream()
                .chatResponse()
                .doFirst(()->stringRedisTemplate.opsForValue().set(chat.getSessionId(),"true"))
                .doOnComplete(()->stringRedisTemplate.delete(chat.getSessionId()))
                .doOnError(throwable->stringRedisTemplate.delete(chat.getSessionId()))
                .takeWhile(response ->
                        Boolean.TRUE.equals(
                                stringRedisTemplate.hasKey(chat.getSessionId())
                        )
                )
                .map(chatResponse -> {
                    String text = chatResponse.getResult().getOutput().getText();
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.just(ChatEventVO.builder()
                        .eventType(ChatEventTypeEnum.STOP.getValue())
                        .build()));
    }

    @Override
    public void stopChat(String sessionId) {
        stringRedisTemplate.delete(sessionId);
    }
}
