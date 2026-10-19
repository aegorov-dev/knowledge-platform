package com.aegorov.knowledgeplatform.ragservice.application.embedding;

/**
 * Adapts a Spring AI {@link org.springframework.ai.embedding.EmbeddingModel}
 * (e.g. OpenAI on platform.openai.com, Ollama) to the local {@link EmbeddingModel} seam,
 * keeping the RAG pipeline provider-agnostic.
 * <p>
 * Parity requirement: when this provider is active, indexing-service MUST embed
 * chunks with the SAME model + dimensions (cosine), otherwise a query embedding
 * produced here cannot be compared against the stored chunk embeddings and
 * retrieval becomes meaningless. See plan.md ("Embedding model migration").
 */
public class SpringAiEmbeddingModelAdapter implements EmbeddingModel {

    private final org.springframework.ai.embedding.EmbeddingModel delegate;

    public SpringAiEmbeddingModelAdapter(org.springframework.ai.embedding.EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
