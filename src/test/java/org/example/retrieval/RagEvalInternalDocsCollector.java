package org.example.retrieval;

import org.example.agent.tool.InternalDocsQueryListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * rag-eval profile 专用的 queryInternalDocs 旁路采集器。
 * 仅存在测试 classpath，仅 rag-eval profile 激活；生产 jar 不包含、不激活。
 * 导出 IT 通过 size()/snapshot(fromIndex) 按 case 切分多次工具调用。
 */
@Component
@Profile("rag-eval")
public class RagEvalInternalDocsCollector implements InternalDocsQueryListener {

    public record QueryCall(String query, List<RetrievedChunk> chunks) {
    }

    private final List<QueryCall> calls = new CopyOnWriteArrayList<>();

    @Override
    public void onQueryInternalDocs(String query, List<RetrievedChunk> chunks) {
        calls.add(new QueryCall(query, chunks == null ? List.of() : List.copyOf(chunks)));
    }

    public int size() {
        return calls.size();
    }

    /** 返回从 fromIndex（含）之后的所有调用，按实际调用顺序。 */
    public List<QueryCall> snapshot(int fromIndex) {
        if (fromIndex < 0 || fromIndex > calls.size()) {
            return List.of();
        }
        return List.copyOf(calls.subList(fromIndex, calls.size()));
    }

    public void clear() {
        calls.clear();
    }
}
