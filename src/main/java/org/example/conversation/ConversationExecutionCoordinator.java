package org.example.conversation;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes requests for the same conversation while keeping lock storage bounded. */
@Component
public class ConversationExecutionCoordinator {

    private static final int STRIPE_COUNT = 256;
    private final Semaphore[] stripes = new Semaphore[STRIPE_COUNT];

    public ConversationExecutionCoordinator() {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Semaphore(1, true);
        }
    }

    public Lease acquire(String conversationId) throws InterruptedException {
        return acquire(conversationId, Instant.now().plusSeconds(60));
    }

    /**
     * 获取会话执行锁；等待时间纳入请求截止时间，防止排队请求无限等待。
     */
    public Lease acquire(String conversationId, Instant deadline) throws InterruptedException {
        int index = (conversationId.hashCode() & Integer.MAX_VALUE) % stripes.length;
        Semaphore semaphore = stripes[index];
        long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMs <= 0 || !semaphore.tryAcquire(remainingMs, TimeUnit.MILLISECONDS)) {
            throw new ConversationLockTimeoutException(conversationId);
        }
        return new Lease(semaphore);
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
