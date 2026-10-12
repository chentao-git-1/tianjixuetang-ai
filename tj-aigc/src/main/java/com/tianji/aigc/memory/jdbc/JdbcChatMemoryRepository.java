package com.tianji.aigc.memory.jdbc;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.aigc.entity.ChatRecord;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.service.impl.ChatRecordServiceImpl;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Wrapper;
import java.util.List;
import java.util.Locale;

@Component
public class JdbcChatMemoryRepository implements ChatMemoryRepository{

    @Resource
    private ChatRecordServiceImpl chatRecordService;

    @Override
    public List<String> findConversationIds() {
        List<ChatRecord> list = chatRecordService.lambdaQuery()
                .select(ChatRecord::getConversationId)
                .list();
        return CollStreamUtil.toList(list,ChatRecord::getConversationId);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {

        List<ChatRecord> list = chatRecordService.lambdaQuery()
                .eq(ChatRecord::getConversationId,conversationId)
                .orderByAsc(ChatRecord::getUpdateTime)
                .list();
        return CollStreamUtil.toList(list,chatRecord -> MessageUtil.toMessage(chatRecord.getData()));
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {

        Long userId = Convert.toLong(StringUtils.substringBefore(conversationId, '_'));



        //先删除原有数据
        deleteByConversationId(conversationId);

        List<ChatRecord> list = CollStreamUtil.toList(messages, message -> ChatRecord.builder()
                .data(MessageUtil.toJson(message))
                .conversationId(conversationId)
                .creater(userId)
                .updater(userId)
                .build());

        chatRecordService.saveBatch(list);


    }

    @Override
    public void deleteByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatRecord> eq = Wrappers.<ChatRecord>lambdaQuery()
                .eq(ChatRecord::getConversationId, conversationId);

        this.chatRecordService.remove(eq);
    }
}
