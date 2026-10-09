# RAG eval baseline

```
queryCount=48 vectorTopN=20 bm25TopN=20 candidateTopN=20 rrfK=60 targetTokens=450 maxTokens=600 overlapTokens=60 embedding=qwen3.7-text-embedding

RRF-only reranker=none Query count=46 HitRate@1=0.478 HitRate@3=0.543 HitRate@5=0.565 Recall@5=0.543 MRR=0.516 MISS=[cpu-002, cpu-004, cpu-005, mem-003, mem-006, disk-003, disk-004, disk-005, disk-007, disk-008, svc-004, svc-005, svc-006, slow-003, slow-004, slow-005, slow-007, mix-001, multi-001, multi-002] NoResultRecall=0/2 NoResultPrecision=n/a

RRF+lexical reranker=lexical Query count=46 HitRate@1=0.500 HitRate@3=0.565 HitRate@5=0.565 Recall@5=0.554 MRR=0.533 MISS=[cpu-002, cpu-003, cpu-004, cpu-005, mem-003, mem-006, disk-004, disk-005, disk-007, disk-008, svc-004, svc-005, svc-006, slow-003, slow-004, slow-005, slow-007, mix-001, multi-001, multi-002] NoResultRecall=0/2 NoResultPrecision=n/a
```
