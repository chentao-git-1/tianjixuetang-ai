package com.tianji.aigc.service;


import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.vo.ChatEventVO;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;


public interface ChatService {
    Flux<ChatEventVO> chat(ChatDTO chat);

    void stopChat(String sessionId);
}
