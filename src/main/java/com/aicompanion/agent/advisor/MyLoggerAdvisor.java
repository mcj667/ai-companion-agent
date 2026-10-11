package com.aicompanion.agent.advisor;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 自定义日志 Advisor：info 级别输出请求 / 响应，并附带耗时与 token 用量。
 *
 * <p>相比内置的 {@code SimpleLoggerAdvisor}：
 * <ul>
 *   <li>用 info 级别（内置的是 debug，默认打不出来）</li>
 *   <li>响应打纯文本 + 模型名 + token + 耗时，而不是整段 JSON</li>
 * </ul>
 */
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MyLoggerAdvisor.class);

    /**
     * 默认 order。内置的 MessageChatMemoryAdvisor 是 0，而 order 越大越靠近模型，
     * 所以取 10 让本 advisor 排在 memory 之后，能看到注入历史后的完整请求。
     */
    private static final int DEFAULT_ORDER = 10;

    private final int order;

    public MyLoggerAdvisor() {
        this(DEFAULT_ORDER);
    }

    public MyLoggerAdvisor(int order) {
        this.order = order;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest loggedRequest = before(request);
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(loggedRequest);
        observeAfter(response, System.nanoTime() - start);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest loggedRequest = before(request);
        long start = System.nanoTime();
        // 聚合在流结束时回调一次，所以此时算出的就是「首字节到最后一字节」的总耗时
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(chain.nextStream(loggedRequest),
                        response -> observeAfter(response, System.nanoTime() - start));
    }

    private ChatClientRequest before(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        log.info("AI Request: user=\"{}\" messages={}", lastUserText(instructions), instructions.size());
        return request;
    }

    private void observeAfter(ChatClientResponse response, long elapsedNanos) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            log.info("AI Response: <empty> elapsed={}ms", toMillis(elapsedNanos));
            return;
        }
        log.info("AI Response: text=\"{}\" model={} tokens[prompt={}, completion={}, total={}] elapsed={}ms",
                outputText(chatResponse),
                model(chatResponse),
                usage(chatResponse, Usage::getPromptTokens),
                usage(chatResponse, Usage::getCompletionTokens),
                usage(chatResponse, Usage::getTotalTokens),
                toMillis(elapsedNanos));
    }

    private static String lastUserText(List<Message> instructions) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return "<none>";
    }

    private static String outputText(ChatResponse chatResponse) {
        if (chatResponse.getResult() == null) {
            return "<none>";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text != null ? text : "<none>";
    }

    private static String model(ChatResponse chatResponse) {
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        return metadata != null ? metadata.getModel() : "<unknown>";
    }

    /** token 数拿不到时（比如 EmptyUsage、或流式未开 usage 统计）统一显示 ? */
    private static String usage(ChatResponse chatResponse, Function<Usage, Integer> getter) {
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata != null ? metadata.getUsage() : null;
        Integer value = usage != null ? getter.apply(usage) : null;
        return value != null ? value.toString() : "?";
    }

    private static long toMillis(long nanos) {
        return nanos / 1_000_000;
    }
}
