package org.example.controller;

import org.example.controller.support.AgentSseStreamer;
import org.example.dto.ChatAnswer;
import org.example.dto.ChatRequest;
import org.example.dto.Result;
import org.example.service.ChatApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天 Agent API。只做 HTTP 映射，校验与执行在 ChatApplicationService。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final long CHAT_STREAM_TIMEOUT_MS = 300_000L;

    private final ChatApplicationService chatApplicationService;
    private final AgentSseStreamer agentSseStreamer;

    public ChatController(ChatApplicationService chatApplicationService, AgentSseStreamer agentSseStreamer) {
        this.chatApplicationService = chatApplicationService;
        this.agentSseStreamer = agentSseStreamer;
    }

    @PostMapping("/chat")
    public ResponseEntity<Result<ChatAnswer>> chat(@RequestBody ChatRequest request) throws Exception {
        return ResponseEntity.ok(Result.ok(chatApplicationService.chat(request)));
    }

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        return agentSseStreamer.startChatStream(request, CHAT_STREAM_TIMEOUT_MS);
    }
}
