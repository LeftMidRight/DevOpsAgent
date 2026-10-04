package org.example.agent.tool;

import org.example.retrieval.RetrievedChunk;

import java.util.List;

/**
 * queryInternalDocs 真实检索结果的旁路监听。
 * 仅供离线评估（rag-eval profile）采集 Agent 实际看到的上下文；
 * 生产环境不注册实现，调用为零开销，不改变检索结果与工具返回。
 */
public interface InternalDocsQueryListener {

    /**
     * 每次真实知识库检索完成后回调（空结果也会回调）。
     * 异常路径不回调。实现必须快速、幂等、只读使用 chunks。
     */
    void onQueryInternalDocs(String query, List<RetrievedChunk> chunks);
}
