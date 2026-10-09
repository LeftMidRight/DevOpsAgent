package org.example.conversation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有上限、按最后访问过期的 JVM 会话缓存。过期或被挤出的会话下次从 PostgreSQL 恢复。
 */
@Component
public class InMemoryRuntimeConversationStore implements RuntimeConversationStore {

    static final int DEFAULT_MAXIMUM_SIZE = 256;
    static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

    private final int maximumSize;
    private final Duration expireAfterAccess;
    private final Clock clock;
    private final ConcurrentHashMap<String, RuntimeConversationState> states = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryRuntimeConversationStore() {
        this(DEFAULT_MAXIMUM_SIZE, DEFAULT_EXPIRE_AFTER_ACCESS, Clock.systemUTC());
    }

    public InMemoryRuntimeConversationStore(int maximumSize, Duration expireAfterAccess, Clock clock) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("会话缓存容量必须大于 0");
        }
        if (expireAfterAccess == null || expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
            throw new IllegalArgumentException("会话缓存过期时间必须大于 0");
        }
        this.maximumSize = maximumSize;
        this.expireAfterAccess = expireAfterAccess;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<RuntimeConversationState> get(String conversationId) {
        RuntimeConversationState state = states.get(conversationId);
        if (state == null) {
            return Optional.empty();
        }
        if (isExpired(state, clock.instant())) {
            states.remove(conversationId, state);
            return Optional.empty();
        }
        state.touch(clock.instant());
        return Optional.of(state);
    }

    @Override
    public synchronized void put(RuntimeConversationState state) {
        state.touch(clock.instant());
        states.put(state.conversationId(), state);
        evict(state.conversationId());
    }

    @Override
    public synchronized void remove(String conversationId) {
        states.remove(conversationId);
    }

    @Override
    public synchronized boolean contains(String conversationId) {
        RuntimeConversationState state = states.get(conversationId);
        if (state == null) {
            return false;
        }
        if (isExpired(state, clock.instant())) {
            states.remove(conversationId, state);
            return false;
        }
        return true;
    }

    private void evict(String protectedId) {
        Instant now = clock.instant();
        states.entrySet().removeIf(entry ->
                !entry.getKey().equals(protectedId) && isExpired(entry.getValue(), now));
        while (states.size() > maximumSize) {
            String oldestId = null;
            Instant oldestAccess = null;
            for (Map.Entry<String, RuntimeConversationState> entry : states.entrySet()) {
                if (entry.getKey().equals(protectedId)) {
                    continue;
                }
                Instant access = entry.getValue().lastAccessAt();
                if (oldestAccess == null || access.isBefore(oldestAccess)) {
                    oldestAccess = access;
                    oldestId = entry.getKey();
                }
            }
            if (oldestId == null) {
                return;
            }
            states.remove(oldestId);
        }
    }

    private boolean isExpired(RuntimeConversationState state, Instant now) {
        return state.lastAccessAt().plus(expireAfterAccess).isBefore(now);
    }
}
