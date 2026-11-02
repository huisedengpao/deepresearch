# Spring AI Alibaba 与 LangChain4j 的 Agent 编排对比

## 编排抽象
- Spring AI Alibaba 基于 **Graph / ReactAgent**，以 StateGraph 显式建模节点、条件边与并行分支，并配套 Interceptor / Hook 体系与官方示例（如 DeepResearch）。
- LangChain4j 以 **Chain 与 AiServices** 为抽象，Agent 主要靠工具调用循环（tool-calling loop）实现，配合 ChatMemory 管理上下文。

## 设计差异
1. **图式编排 vs 链式编排**：Spring AI Alibaba 显式建模状态图，支持条件边与并行分支；LangChain4j 更偏顺序链与函数组合，复杂分支需自行编排。
2. **状态持久化粒度**：Spring AI Alibaba 提供 Redis / MySQL 等 Checkpointer / Saver，可按 thread 跨进程恢复；LangChain4j 以 ChatMemory + 外部存储为主，状态粒度相对粗。

## 适用场景
需要多 Agent 协作、复杂条件分支与可恢复执行时选 Spring AI Alibaba；轻量工具调用、生态以 Java 为主、追求简洁时选 LangChain4j。

## Sources
- [Spring AI Alibaba](https://java2ai.com/)
- [LangChain4j](https://docs.langchain4j.dev/)
