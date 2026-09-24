package org.example.controller;

import org.example.agent.TaskMode;
import org.example.controller.support.AgentSseStreamer;
import org.example.support.ChatRequestSupport;
import org.example.dto.ChatAnswer;
import org.example.dto.ChatRequest;
import org.example.dto.Result;
import org.example.dto.SseMessage;
import org.example.service.ChatApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天与运维 Agent API。只负责 HTTP 映射与最薄的一层适配，
 * 业务执行委托 ChatApplicationService / AgentSseStreamer。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final long CHAT_STREAM_TIMEOUT_MS = 300_000L;
    private static final long OPS_STREAM_TIMEOUT_MS = 600_000L;

    private final ChatApplicationService chatApplicationService;
    private final AgentSseStreamer agentSseStreamer;

    public ChatController(ChatApplicationService chatApplicationService, AgentSseStreamer agentSseStreamer) {
        this.chatApplicationService = chatApplicationService;
        this.agentSseStreamer = agentSseStreamer;
    }

    @PostMapping("/chat")
    public ResponseEntity<Result<ChatAnswer>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}, Mode: {}",
                    request.getId(), request.getQuestion(), request.getMode());

            if (ChatRequestSupport.hasBlankQuestion(request)) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(Result.fail(400, "问题内容不能为空"));
            }

            TaskMode mode = ChatRequestSupport.parseMode(request);
            var result = chatApplicationService.chat(request, mode);
            return ResponseEntity.ok(Result.ok(
                    new ChatAnswer(result.conversationId(), result.answer(), result.reasoning())));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("对话失败", e);
            return ResponseEntity.ok(Result.fail(e.getMessage()));
        }
    }

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        if (ChatRequestSupport.hasBlankQuestion(request)) {
            logger.warn("问题内容为空");
            SseEmitter emitter = new SseEmitter(CHAT_STREAM_TIMEOUT_MS);
            agentSseStreamer.sendAndComplete(emitter, SseMessage.error("问题内容不能为空"));
            return emitter;
        }

        TaskMode mode = ChatRequestSupport.parseMode(request);
        return agentSseStreamer.startStream(
                request.getId(),
                ChatRequestSupport.normalizedQuestion(request),
                mode,
                CHAT_STREAM_TIMEOUT_MS);
    }

    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestBody(required = false) ChatRequest request) {
        String question = ChatRequestSupport.resolveOpsQuestion(request);
        return agentSseStreamer.startStream(
                ChatRequestSupport.sessionId(request),
                question,
                TaskMode.OPS,
                OPS_STREAM_TIMEOUT_MS);
    }
}
