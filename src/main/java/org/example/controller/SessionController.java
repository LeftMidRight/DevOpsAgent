package org.example.controller;

import org.example.dto.ClearRequest;
import org.example.dto.Result;
import org.example.dto.SessionInfoResponse;
import org.example.service.SessionApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理 API。校验与会话操作在 SessionApplicationService。
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    private final SessionApplicationService sessionApplicationService;

    public SessionController(SessionApplicationService sessionApplicationService) {
        this.sessionApplicationService = sessionApplicationService;
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<Result<String>> clearChatHistory(@RequestBody ClearRequest request) throws Exception {
        sessionApplicationService.clearHistory(request);
        return ResponseEntity.ok(Result.ok("会话历史已清空"));
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<Result<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        return ResponseEntity.ok(Result.ok(sessionApplicationService.getSessionInfo(sessionId)));
    }
}
