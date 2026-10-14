package com.tianji.aigc.service.impl;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {



    private final ChatClient chatClient;

    private final SystemPromptConfig systemPromptConfig;

//    private static final Map<String,Boolean> GENERATE_STATUS=new ConcurrentHashMap<>();

    private final  StringRedisTemplate stringRedisTemplate;

    private final ChatMemory chatMemory;

    // 输出结束的标记
    private static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    private final VectorStore vectorStore;




    @Override
    public Flux<ChatEventVO> chat(ChatDTO chat) {

        // 获取对话id
        String conversationId = ChatService.getConversationId(chat.getSessionId());

        String requestId = IdUtil.simpleUUID();


        // 创建RAG增强
        var qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.6d).topK(6).build())
                .build();

        // 获取用户id
        var userId = UserContext.getUser();

        // 大模型输出内容的缓存器
        var outputBuilder = new StringBuilder();

        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get())
                        .params(Map.of("now", DateUtil.now()))
                )
                .user(chat.getQuestion())

                // 设置对话记忆中的对话id
                .advisors(advisor ->
                        advisor
                                .advisors(qaAdvisor)
                                .param(
                        ChatMemory.CONVERSATION_ID, conversationId
                ))

                // 通过工具上下文传递参数
                .toolContext(Map.of(
                        Constant.REQUEST_ID, requestId,
                        Constant.USER_ID, userId
                ))

                .stream()
                .chatResponse()

                // 开始输出时，在Redis中设置标记
                .doFirst(() -> stringRedisTemplate.opsForValue()
                        .set(chat.getSessionId(), "true"))

                // 输出取消时保存已经生成的内容
                .doOnCancel(() -> {
                    this.saveStopHistoryRecord(
                            conversationId,
                            outputBuilder.toString()
                    );
                })

                // 判断是否继续输出
                .takeWhile(response -> Boolean.TRUE.equals(
                        stringRedisTemplate.hasKey(chat.getSessionId())
                ))

                // 将大模型输出转换为前端响应对象
                .map(chatResponse -> {

                    String text = chatResponse.getResult()
                            .getOutput()
                            .getText();

                    outputBuilder.append(text);

                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })

                // 输出结束后追加工具参数和停止事件
                .concatWith(Flux.defer(() -> {

                    var map = ToolResultHolder.get(requestId);

                    if (CollUtil.isNotEmpty(map)) {

                        ToolResultHolder.remove(requestId);

                        ChatEventVO chatEventVO = ChatEventVO.builder()
                                .eventData(map)
                                .eventType(ChatEventTypeEnum.PARAM.getValue())
                                .build();

                        return Flux.just(chatEventVO, STOP_EVENT);
                    }

                    return Flux.just(STOP_EVENT);
                }))

                // 完成、异常或取消时清理Redis标记
                .doFinally(signalType ->
                        stringRedisTemplate.delete(chat.getSessionId())
                );
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
