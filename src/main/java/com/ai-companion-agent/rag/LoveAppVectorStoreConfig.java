package com.example.aiagent.rag;

import java.io.File;
import java.util.List;

import com.example.aiagent.prompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 准备向量库：把知识库的文字变成向量存起来，供 RAG 检索用。
 *
 * <p>对话和向量化是两条链路：对话走 DeepSeek，但 DeepSeek 没有 embedding 接口，
 * 所以这里注入的是阿里百炼的 {@link EmbeddingModel}。
 *
 * <p>向量库用 {@link SimpleVectorStore}（内存版，能存成文件）。不用 Redis 是因为
 * Redis 做向量检索要额外装 RediSearch 模块，本机没有。
 */
@Configuration
@Slf4j
public class LoveAppVectorStoreConfig {

    /** 向量库存到哪个文件 */
    private static final File STORE_FILE = new File("data/love-app-vector-store.json");

    /** application.yaml 里 spring.ai.dashscope.api-key 的默认占位值（表示"没配"） */
    private static final String PLACEHOLDER_API_KEY = "not-configured";

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Bean
    public VectorStore loveAppVectorStore(EmbeddingModel embeddingModel,
            com.example.aiagent.rag.LoveAppDocumentLoader documentLoader) {

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        // 文件已经存在就直接读进来，不用再调 embedding 接口（省钱，启动也快）。
        // 如果哪天文件坏了导致起不来，删掉这个文件重启一次就行。
        if (STORE_FILE.exists()) {
            store.load(STORE_FILE);
            log.info("向量库从文件载入完成");
            return store;
        }

        // 没配 key 就别去调接口了：会 401 失败，还要白等几秒、日志里刷一大段异常。
        // 这里只是"跳过"，不是"出错"——对话、流式、报告那些功能都不受影响。
        if (this.dashscopeApiKey.isBlank() || PLACEHOLDER_API_KEY.equals(this.dashscopeApiKey)) {
            log.info("没有配置 DASHSCOPE_API_KEY，跳过向量化：RAG 检索不可用，其他功能不受影响。"
                    + "（想启用的话配好 key 重启即可，申请地址 https://bailian.console.aliyun.com/ ）");
            return store;
        }

        // 第一次启动：读文档 → 向量化 → 存文件
        try {
            List<Document> documents = documentLoader.loadMarkdowns();
            store.add(documents);          // 这一步会去调百炼的 embedding 接口
            STORE_FILE.getParentFile().mkdirs();
            store.save(STORE_FILE);
            log.info("知识库共 {} 段，已向量化并保存到 {}", documents.size(), STORE_FILE.getPath());
        }
        catch (RuntimeException e) {
            // 没配 DASHSCOPE_API_KEY、key 失效、断网都会走到这里。
            // RAG 只是附加功能，不该把已经能用的对话功能一起拖垮，所以这里只记日志。
            log.error("向量化失败，RAG 暂时不可用（对话功能不受影响）", e);
        }
        return store;
    }

    /**
     * 检索 Advisor：每次提问前，先去向量库找最相关的几段资料，拼进提示词再发给模型。
     *
     * <p>用自定义的 {@link prompts#RAG_ANSWER} 而不是默认模板，原因见那个常量的注释——
     * 默认模板不说"这堆正文是什么"，模型就会把"只有 4 段"误解成"没有这份文档"。
     */
    @Bean
    public QuestionAnswerAdvisor loveAppQuestionAnswerAdvisor(VectorStore loveAppVectorStore) {
        return QuestionAnswerAdvisor.builder(loveAppVectorStore)
                .promptTemplate(new PromptTemplate(prompts.RAG_ANSWER))
                .build();
    }

}
