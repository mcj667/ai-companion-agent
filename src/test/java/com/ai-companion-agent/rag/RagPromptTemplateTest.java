package com.example.aiagent.rag;

import java.util.Map;

import com.example.aiagent.prompts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 RAG 提示词模板里的占位符 {@code {question_answer_context}}。
 *
 * <p><b>为什么值得单独写个测试：</b>这个占位符是 {@code QuestionAnswerAdvisor} 和模板之间的
 * 唯一约定，而 Spring AI 的模板渲染是<b>严格模式</b>——模板里出现一个没被赋值的变量，
 * {@code render()} 会直接抛异常。也就是说占位符写错一个字母，不是"提示词变差"，
 * 而是<b>每次 RAG 提问都 500</b>。
 *
 * <p>纯单元测试，不连 Spring 也不连 Redis，跑一次几十毫秒。
 */
class RagPromptTemplateTest {

    @Test
    @DisplayName("占位符能被替换掉，渲染后不残留花括号")
    void rendersRetrievedContextIntoPlaceholder() {
        String rendered = new PromptTemplate(prompts.RAG_ANSWER)
                .render(Map.of("question_answer_context", "【这里是检索到的正文】"));

        assertThat(rendered).contains("【这里是检索到的正文】");
        assertThat(rendered).doesNotContain("{question_answer_context}");
        // 模板里只有这一个占位符，渲染完不该再有任何没被替换的花括号
        assertThat(rendered).doesNotContain("{");
    }
}
