package com.example.aiagent.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 读知识库：把 resources/document 下的 md 文件，读成一段段文字。
 *
 * <p>切分规则是"每遇到一个标题就切一刀"，所以一篇文章会变成好几段。
 * 切段是为了后面检索更准——用户问的是某一个具体问题，不需要整篇文章。
 */
@Component
@Slf4j
public class LoveAppDocumentLoader {

    public List<Document> loadMarkdowns() {
        // 找出 document 目录下所有 md 文件（classpath*: 表示连 jar 包里的也一起找）
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources("classpath*:document/*.md");
        }
        catch (IOException e) {
            throw new IllegalStateException("找不到 document 目录", e);
        }

        List<Document> documents = new ArrayList<>();
        for (Resource file : files) {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)   // 遇到 --- 也切成新的一段
                    .withIncludeCodeBlock(false)               // 代码块不要
                    .withIncludeBlockquote(false)              // 引用块不要
                    .withAdditionalMetadata("filename", file.getFilename())   // 记下来源文件名
                    .build();

            documents.addAll(new MarkdownDocumentReader(file, config).get());
        }

        log.info("知识库加载完成：{} 个文件，共 {} 段", files.length, documents.size());
        return documents;
    }

}
