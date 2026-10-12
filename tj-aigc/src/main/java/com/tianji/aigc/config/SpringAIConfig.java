package com.tianji.aigc.config;

import com.tianji.aigc.memory.RedisChatMemoryRepository;
import com.tianji.aigc.memory.jdbc.JdbcChatMemoryRepository;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.aigc.tools.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.validation.Valid;

@Configuration
public class SpringAIConfig {

    @Value("${tj.ai.memory.max:100}")
    private Integer maxMessages;

    /**
     * 配置 ChatClient
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor loggerAdvisor,
                                 Advisor messageChatMemoryAdvisor,
                                 CourseTools courseTools ,// 课程工具,
                                 OrderTools orderTools // 预下单工具
    ) {  // 日志记录器
        return chatClientBuilder
                .defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor) //添加 Advisor 功能增强
                .defaultTools(courseTools, orderTools) //添加默认工具
                .build();
    }

    /**
     * 日志记录器
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.typr",value = "type",havingValue = "Redis")
    public RedisChatMemoryRepository redisChatMemoryRepository(){
        return new RedisChatMemoryRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.typr",value = "type",havingValue = "Mysql")
    public JdbcChatMemoryRepository jdbcChatMemoryRepository(){
        return new JdbcChatMemoryRepository();
    }


    @Bean ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository){
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(this.maxMessages)
                .build();

    }

    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory){
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
