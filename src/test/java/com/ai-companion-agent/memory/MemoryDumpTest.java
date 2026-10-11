package com.example.aiagent.memory;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 排障用：把某个会话在 Redis 里实际存了什么打印出来。
 * Kryo 是二进制，redis-cli 里 GET 出来是乱码，只能靠它读。
 */
@SpringBootTest
class MemoryDumpTest {

    @Autowired
    private RedisChatMemory redisChatMemory;

    @Test
    void dumpConversation() {
        String conversationId = "1001";
        List<Message> history = redisChatMemory.get(conversationId);

        System.out.println("========== chatId=" + conversationId + " 共 " + history.size() + " 条 ==========");
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            System.out.println("[%2d] %-9s %s".formatted(i, message.getMessageType(), message.getText()));
        }
        System.out.println("========== dump 结束 ==========");
    }
}
