package org.example.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class JsonlTrajectoryStore {

    private static final Logger logger = LoggerFactory.getLogger(JsonlTrajectoryStore.class);
    private static final int LOCK_STRIPES = 128;

    private final ObjectMapper objectMapper;
    private final TrajectoryProperties properties;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public JsonlTrajectoryStore(ObjectMapper objectMapper, TrajectoryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public boolean append(TrajectoryEvent event) {
        if (!properties.isEnabled()) {
            return false;
        }

        Path path = pathForConversation(event.conversationId());
        ReentrantLock lock = locks[(path.hashCode() & Integer.MAX_VALUE) % locks.length];
        lock.lock();
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            byte[] line = (objectMapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                if (properties.isForceOnWrite()) {
                    channel.force(false);
                }
            }
            return true;
        } catch (IOException e) {
            // Trajectory persistence is observability. A local disk failure must
            // not turn a valid user request into an Agent failure.
            logger.error("追加 Agent 轨迹失败 - RunId: {}, EventType: {}", event.runId(), event.type(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    public Path pathForConversation(String conversationId) {
        String hash = sha256(conversationId);
        Path base = Path.of(properties.getBaseDir()).toAbsolutePath().normalize();
        return base.resolve(hash.substring(0, 2)).resolve(hash + ".jsonl");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
