package com.aicompanion.agent.app;

import com.aicompanion.agent.LoveReport;
import com.aicompanion.agent.advisor.MyLoggerAdvisor;
import com.aicompanion.agent.memory.RedisChatMemory;
import com.aicompanion.agent.prompts;
import com.aicompanion.agent.tool.FileReadTool;
import com.aicompanion.agent.tool.WebPageTool;
import com.aicompanion.agent.tool.WebSearchTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;


@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;

    /** 检索增强问答用的 Advisor，只挂在 doChatWithRag 上，不影响其他对话入口 */
    private final QuestionAnswerAdvisor questionAnswerAdvisor;

    /**
     * 联网搜索 / 网页抓取两个工具，只挂在 {@link #doChatWithSearch} 上。
     *
     * <p>为什么不挂在 {@code ChatClient.builder().defaultTools(...)} 上做成"默认都带"：
     * 那样普通对话、流式、RAG、报告全都会带上工具，每次闲聊都要多花一次模型判断的钱，
     * 而且演示时看不出"用了工具"和"没用工具"的区别。按请求挂也更贴合项目的既有写法
     * （{@code doChatWithRag} 里的 {@code .advisors(questionAnswerAdvisor)} 就是这么挂的）。
     */
    private final WebSearchTool webSearchTool;

    private final WebPageTool webPageTool;

    private final FileReadTool fileReadTool;

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    public LoveApp(ChatModel chatModel, RedisChatMemory redisChatMemory,
                   QuestionAnswerAdvisor questionAnswerAdvisor,
                   WebSearchTool webSearchTool, WebPageTool webPageTool, FileReadTool fileReadTool) {
        this.questionAnswerAdvisor = questionAnswerAdvisor;
        this.webSearchTool = webSearchTool;
        this.webPageTool = webPageTool;
        this.fileReadTool = fileReadTool;
        chatClient = ChatClient.builder(chatModel)
                .defaultSystem(prompts.SYSTEM)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(redisChatMemory).build(),
                        new MyLoggerAdvisor())
                .build();
    }

    public String doChat(String message, String chatId) {
        String content = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();

        log.info("content: {}", content);
        return content;
    }

    /**
     * 检索增强问答（RAG）：回答前先去向量库查资料。
     *
     * <p>和 {@link #doChat} 只差一行——多挂了一个 {@link QuestionAnswerAdvisor}，
     * 它会自己完成"检索 + 拼进提示词"，所以我们这里看不到检索的代码。
     */
    public String doChatWithRag(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(questionAnswerAdvisor)
                .call()
                .content();
    }

    /**
     * 联网搜索：把两个工具交给模型，由<b>模型自己决定</b>要不要调、调几次。
     *
     * <p>这里只多了一行 {@code .tools(...)}，剩下的全在框架里：模型说要调 webSearch，
     * Spring AI 就反射调我们那个 Java 方法、把返回值塞回对话，再问一次模型。
     * 所以这个方法看起来只有一次调用，实际可能发生两到三次模型请求。
     *
     * <p>用非流式（跟 {@link #doChat}、{@link #doChatWithRag} 一致）：工具调用本来就要等
     * 网络往返，逐字输出的意义不大，而流式下"工具调用的中间态"处理起来更绕。
     */
    public String doChatWithSearch(String message, String chatId) {
        String content = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .tools(this.webSearchTool, this.webPageTool)
                .call()
                .content();

        log.info("content: {}", content);
        return content;
    }

    /**
     * 结构化输出：让模型直接返回一个 LoveReport 对象，而不是一段自然语言。
     *
     * <p>entity() 会自动往提示词里追加 JSON schema 说明，再把模型返回的 JSON
     * 反序列化成 LoveReport 记录，省掉手工解析字符串。
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport report = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);

        log.info("loveReport: {}", report);
        return report;
    }

    /**
     * 流式输出：逐字返回，前端可以边生成边显示。
     *
     * <p>对话记忆同样生效——MessageChatMemoryAdvisor 也实现了 StreamAdvisor，
     * 会在流结束后把这一轮存进 Redis。
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }


    /**
     * MCP 模式：用 MCP 服务提供的工具来回答。
     *
     * <p>跟 {@link #doChatWithSearch} 逐行对比，<b>只有一处不同</b>：那边是
     * {@code .tools(两个本地对象)}，这边是 {@code .toolCallbacks(provider)}。
     * 但对模型来说两者没有任何区别——它看到的都是一张"可用工具清单"，
     * 一样是它自己决定要不要调。
     *
     * <p>真正的差别在<b>这些方法根本不在这个项目里</b>：{@code toolCallbackProvider}
     * 里的工具是应用启动时连上 MCP server、由对方"报"过来的。所以这边连工具叫什么都
     * 不用写，新增工具也不用改 Java 代码。
     *
     * <p>用 {@code chatResponse()} 而不是 {@code content()}：两者取到的文字是一样的，
     * 前者多保留了完整响应结构，需要看工具调用细节时用得上。
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
