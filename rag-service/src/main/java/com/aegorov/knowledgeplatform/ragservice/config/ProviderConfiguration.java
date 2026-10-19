package com.aegorov.knowledgeplatform.ragservice.config;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aegorov.knowledgeplatform.ragservice.application.embedding.AzureOpenAiEmbeddingModel;
import com.aegorov.knowledgeplatform.ragservice.application.embedding.EmbeddingModel;
import com.aegorov.knowledgeplatform.ragservice.application.embedding.SpringAiEmbeddingModelAdapter;
import com.aegorov.knowledgeplatform.ragservice.application.embedding.StubEmbeddingModel;
import com.aegorov.knowledgeplatform.ragservice.application.llm.AzureOpenAiChatLlm;
import com.aegorov.knowledgeplatform.ragservice.application.llm.ChatLlm;
import com.aegorov.knowledgeplatform.ragservice.application.llm.SpringAiChatLlm;
import com.aegorov.knowledgeplatform.ragservice.application.llm.StubChatLlm;

/**
 * Selects the active LLM and embedding providers via
 * {@code knowledge-platform.llm.provider} and
 * {@code knowledge-platform.embedding.provider}. Both default to offline stubs so
 * the whole demo runs with no API keys. {@code openai} wires real
 * platform.openai.com models through Spring AI (key from
 * {@code spring.ai.openai.api-key}, empty by default); {@code ollama} wires a local
 * Ollama instance ({@code spring.ai.ollama.*}). The {@code azure} beans are
 * inert placeholders (see the model classes). Nothing is committed and nothing
 * calls out unless a provider is explicitly set to a real value.
 */
@Configuration
public class ProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.embedding", name = "provider",
            havingValue = "stub", matchIfMissing = true)
    EmbeddingModel stubEmbeddingModel(EmbeddingProperties embeddingProperties) {
        return new StubEmbeddingModel(embeddingProperties.dimensions());
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.embedding", name = "provider",
            havingValue = "openai")
    EmbeddingModel openAiEmbeddingSeam(OpenAiEmbeddingModel delegate) {
        return new SpringAiEmbeddingModelAdapter(delegate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.embedding", name = "provider",
            havingValue = "ollama")
    EmbeddingModel ollamaEmbeddingSeam(OllamaEmbeddingModel delegate) {
        return new SpringAiEmbeddingModelAdapter(delegate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.embedding", name = "provider",
            havingValue = "azure")
    EmbeddingModel azureEmbeddingModel(
            EmbeddingProperties embeddingProperties,
            @Value("${spring.ai.azure.openai.endpoint:}") String endpoint,
            @Value("${spring.ai.azure.openai.embedding.options.deployment-name:}") String deployment) {
        return new AzureOpenAiEmbeddingModel(embeddingProperties.dimensions(), endpoint, deployment);
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.llm", name = "provider",
            havingValue = "stub", matchIfMissing = true)
    ChatLlm stubChatLlm() {
        return new StubChatLlm();
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.llm", name = "provider",
            havingValue = "openai")
    ChatLlm openAiChatSeam(OpenAiChatModel delegate) {
        return new SpringAiChatLlm(delegate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.llm", name = "provider",
            havingValue = "ollama")
    ChatLlm ollamaChatSeam(OllamaChatModel delegate) {
        return new SpringAiChatLlm(delegate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge-platform.llm", name = "provider",
            havingValue = "azure")
    ChatLlm azureChatLlm(
            @Value("${spring.ai.azure.openai.endpoint:}") String endpoint,
            @Value("${spring.ai.azure.openai.chat.options.deployment-name:}") String deployment) {
        return new AzureOpenAiChatLlm(endpoint, deployment);
    }
}
