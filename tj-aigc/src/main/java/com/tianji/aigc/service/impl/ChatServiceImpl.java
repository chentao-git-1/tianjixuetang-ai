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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Value;
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

    private final ChatMemory chatMemory;


    @Override
    public Flux<ChatEventVO> chat(ChatDTO chat) {
        //获取对话id
        String conversationId = ChatService.getConversationId(chat.getSessionId());

        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        var outputBuilder = new StringBuilder();


        return this.chatClient.prompt()
                .system(promptSystem-> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get())
                        .params(Map.of("now", DateUtil.now()))
                )
                .user(chat.getQuestion())

                .advisors(advisor->advisor.param(ChatMemory.CONVERSATION_ID,conversationId))//设置对话记忆中对话id

                .stream()
                .chatResponse()
                .doFirst(()->stringRedisTemplate.opsForValue().set(chat.getSessionId(),"true"))
                .doOnCancel(() -> {
                    // 当输出被取消时，保存输出的内容到历史记录中
                    this.saveStopHistoryRecord(conversationId, outputBuilder.toString());
                })
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

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 会话id
     * @param content        大模型输出的内容
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        this.chatMemory.add(conversationId, new AssistantMessage(content));
    }
}
