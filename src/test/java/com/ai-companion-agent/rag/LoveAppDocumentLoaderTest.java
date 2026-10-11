package com.example.aiagent.rag;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测知识库能不能正常读进来。
 *
 * <p>不启动 Spring 容器：直接 new 一个 loader 就能跑，不用把 Redis、DeepSeek 都拉起来。
 */
class LoveAppDocumentLoaderTest {

    private static List<Document> documents;

    @BeforeAll
    static void loadOnce() {
        documents = new LoveAppDocumentLoader().loadMarkdowns();
    }

    @Test
    @DisplayName("三个 md 文件都读到了")
    void loadsAllThreeFiles() {
        assertThat(documents).isNotEmpty();
        assertThat(documents.stream().map(doc -> doc.getMetadata().get("filename")).distinct())
                .containsExactlyInAnyOrder(
                        "恋爱常见问题和回答 - 单身篇.md",
                        "恋爱常见问题和回答 - 恋爱篇.md",
                        "恋爱常见问题和回答 - 已婚篇.md");
    }

    @Test
    @DisplayName("每一篇都切成了好几段，而且正文不为空")
    void splitsIntoSeveralChunks() {
        // 三篇文档一共 15 个问题，切完肯定远不止 3 段
        assertThat(documents.size()).isGreaterThan(3);

        assertThat(documents).allSatisfy(doc -> {
            assertThat(doc.getMetadata().get("filename")).isNotNull();
            assertThat(doc.getText()).isNotBlank();
        });
    }
}
