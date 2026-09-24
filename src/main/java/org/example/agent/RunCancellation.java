package org.example.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 请求级取消句柄。由 SSE 超时、客户端断线或显式取消设置，
 * 不依赖跨 Reactor 线程的 ThreadLocal。
 */
public final class RunCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
