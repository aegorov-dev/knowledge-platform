package com.aegorov.knowledgeplatform.ragservice.controller;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import com.openai.errors.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aegorov.knowledgeplatform.ragservice.application.ConversationAccessDeniedException;
import com.aegorov.knowledgeplatform.ragservice.application.ConversationNotFoundException;
import com.aegorov.knowledgeplatform.ragservice.application.rag.RagChatService;
import com.aegorov.knowledgeplatform.ragservice.application.rag.dto.RagChatChunk;
import com.aegorov.knowledgeplatform.ragservice.application.rag.dto.SourceRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The RAG pipeline HTTP surface.
 * <ul>
 *   <li>{@code POST /api/v1/rag/chat} streams an NDJSON answer (META, SOURCES,
 *       DELTA*, DONE/ERROR).</li>
 *   <li>{@code POST /api/v1/rag/search} returns ranked sources without calling the
 *       LLM, so retrieval is demonstrable and testable on its own.</li>
 * </ul>
 * Caller identity is the authenticated principal (no client-supplied user header).
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagChatController {

    private final RagChatService ragChatService;
    private final ObjectMapper objectMapper;

    public RagChatController(RagChatService ragChatService, ObjectMapper objectMapper) {
        this.ragChatService = ragChatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<String> chat(Principal principal, @Valid @RequestBody ChatRequest request) {
        return ragChatService.chat(userId(principal), request.conversationId(), request.question())
                .map(this::toNdjsonLine);
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<SourceRef>> search(@Valid @RequestBody SearchRequest request) {
        return ragChatService.search(request.question(), request.topK());
    }

    private String toNdjsonLine(RagChatChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk) + "\n";
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize chat chunk", ex);
        }
    }

    private static String userId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ConversationAccessDeniedException("Authentication is required");
        }
        return principal.getName();
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(RuntimeException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(ConversationAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden(RuntimeException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public String openAiUnauthorized(RuntimeException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException ex) {
        return ex.getMessage();
    }

    public record ChatRequest(
            UUID conversationId,
            @NotBlank String question
    ) {
    }

    public record SearchRequest(
            @NotBlank String question,
            Integer topK
    ) {
    }
}
