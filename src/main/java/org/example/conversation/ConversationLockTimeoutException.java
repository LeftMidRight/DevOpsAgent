package org.example.conversation;

/**
 * 同一会话的执行锁在截止时间前未能获得。
 */
public class ConversationLockTimeoutException extends RuntimeException {

    public ConversationLockTimeoutException(String conversationId) {
        super("等待会话锁超时: " + conversationId);
    }
}
