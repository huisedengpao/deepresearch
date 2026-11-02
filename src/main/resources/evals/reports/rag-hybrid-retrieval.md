# RAG 中向量检索与 BM25 的优劣及混合策略

## 向量检索
优势：语义召回，能命中同义改写与近义表达；劣势：精确术语、编号、数字容易漏，且依赖 embedding 质量。

## BM25
优势：精确关键词匹配、可解释、无需向量化、成本低；劣势：不懂语义，近义词与改写召回弱。

## 混合策略
用 **RRF（倒数排名融合）** 合并两路召回结果，再经 rerank 精排后送入生成模型，兼顾语义与精确匹配。

## 适用场景
语义问答优先向量检索；术语/编号/人名检索优先 BM25；通用场景采用混合检索。

## Sources
- [Reciprocal Rank Fusion](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)
