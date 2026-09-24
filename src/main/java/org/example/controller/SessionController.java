package org.example.controller;

import org.example.dto.Result;
import org.example.dto.ClearRequest;
import org.example.dto.SessionInfoResponse;
import org.example.service.SessionApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 会话管理 API，与 Agent 聊天/运维入口分离。
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);

    private final SessionApplicationService sessionApplicationService;

    public SessionController(SessionApplicationService sessionApplicationService) {
        this.sessionApplicationService = sessionApplicationService;
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<Result<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(Result.fail(400, "会话ID不能为空"));
            }

            sessionApplicationService.clearHistory(request.getId());
            return ResponseEntity.ok(Result.ok("会话历史已清空"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(Result.fail(e.getMessage()));
        }
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<Result<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);
            return ResponseEntity.ok(Result.ok(sessionApplicationService.getSessionInfo(sessionId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(Result.fail(e.getMessage()));
        }
    }
}
