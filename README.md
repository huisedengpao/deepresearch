# DeepResearch 多智能体深度研究服务（二次开发版）

> 本仓库基于 Spring AI Alibaba 官方示例 [`examples/deepresearch`](https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/deepresearch)（Apache-2.0）二次开发。
> 官方示例提供了 ReAct 主 Agent、子 Agent 协作与文件系统工具的骨架；**下表所有改造项与实测数据均为本人完成**，改造点在代码注释与提交记录中可逐条对应。

## 与官方示例的差异（Delta 清单）

| # | 改造 | 代码位置 | 实测结果 |
|---|------|----------|----------|
| 1 | **强制并行派发**：主 Agent 单条消息并行派发全部子问题，工具并行执行 | `DeepResearchAgent.java`：`.parallelToolExecution(true)` + research-agent 派发提示词改造 | 端到端墙钟 **241s → 194s（-19.5%）**；SSE token 聚合验证主循环仅 5 次 LLM 调用即收敛 |
| 2 | **风险分级 HITL 审批**：只读检索类工具自动放行，仅落盘写操作中断等待人工批准 | `HumanInTheLoopHook` 风险分级配置 | 定位并修复框架级缺陷「审批中断后 resume 清零工具计数 → 终止条件失效 → 无限搜索不收敛」；实测 **38 次搜索零非预期中断、仅 3 次人工批准收敛** |
| 3 | **会话状态持久化**：MemorySaver → RedisSaver（Redisson） | `.saver(RedisSaver.builder().redisson(...).build())` | 杀掉 JVM 重启后同 threadId 续问**正确恢复上文**；新 threadId 对照组无记忆（线程隔离）；两轮对话 checkpoint 约 **18KB** |
| 4 | **三档模型路由** | `buildModel(qwen-plus / qwen-flash / qwen-max)` | flash=历史摘要（SummarizationHook）、plus=主流程与检索子 Agent、max=报告评审，按「任务难度 × 调用频次」分配成本 |
| 5 | **真实联网检索**：接入百炼 WebSearch MCP（国内可达） | `application.yml`（含 URI.resolve 合并规则的 endpoint 坑位说明）+ `Application.java` Authorization customizer | 替换官方示例的 Jina 海外方案；MCP 禁用时由 `ToolCallbackFallbackConfig` 提供空 Provider 兜底，服务可降级启动 |
| 6 | **SSE 流式过程可视化前端**：单文件、零依赖 | `src/main/resources/static/process.html` | 按时间线渲染主/子 Agent 思考、工具调用状态、实时耗时与 token 消耗，解决官方 chatui「看不到 Agent 在想什么」的问题 |
| 7 | **writer / critique / reviser 报告流水线** | `SubAgentSpec`（research / writer / critique / reviser） | 报告生成、评审、修订角色分离，评审用 qwen-max 档 |

## 实验记录（含负结果）

全部实验遵循**单变量对照 + 运行日志证据链**方法论：

- **并行派发对照**：其余条件不变，仅切换派发策略，端到端墙钟 241s → 194s。收益上限符合 Amdahl 定律——串行段（规划/聚合/评审）决定整体加速比。
- **HITL 死锁排查**：复现稳定路径 → 对照有无审批两条链路日志 → 阅读框架源码，定位 resume 路径清零工具调用计数、`ToolCallLimitHook` 终止条件永不触发；修复后 38 次搜索零非预期中断。
- **持久化对照**：重启 JVM 后同 threadId 恢复上文 / 新 threadId 无记忆，两组对照验证跨进程恢复与线程隔离。
- **Hybrid RAG 全量落地与回退（负结果，如实记录）**：向量 + BM25 双通道召回、RRF 融合、rerank 精排全量接入后，实测墙钟 **+180%**、报告引用数 **6 → 3**。根因定位：抓取语料约 **75% 被 CSDN 反爬拦截**，低质内容入库使精排失去意义、纯增延迟。基于数据**主动回退**到联网检索主链路。教训：RAG 收益前提是语料质量，先评估数据源可靠性再谈检索技巧；此类决策暴露了缺乏系统评测集的问题（已列入 Roadmap）。
- **角色流水线化实验**：验证 writer/critique/reviser 各阶段产物成立；过程中定位到框架并行聚合参数损坏缺陷（HTTP 400），已如实记录。

## 架构

```
研究问题 → 规划（主 Agent 拆解子问题，TodoListInterceptor 跟踪）
        → 并行检索（research-agent × N，WebSearch MCP 真实联网）
        → 报告生成（writer，带引用）
        → 反思评审（critique → reviser，qwen-max）
```

- **编排**：Spring AI Alibaba 1.1.2.2 agent-framework（ReactAgent + Interceptor/Hook 体系）
- **上下文管理**：大结果落盘（>5000 token 转文件系统）、历史摘要压缩（120k token 触发，flash 档）、上下文编辑
- **状态**：Redis checkpoint（RedisSaver），跨进程可恢复、按 threadId 隔离
- **审批**：HITL 风险分级——只读放行 / 写操作中断
- **可观测**：process.html 消费 `/run_sse` 事件流，token/耗时实时可见

## 快速开始

前置条件：

- JDK 17+、Maven、本地 Redis（`127.0.0.1:6379`）
- `AI_DASHSCOPE_API_KEY`（[阿里云百炼](https://bailian.console.aliyun.com/)）
- 百炼控制台开通 **MCP 广场 → 联网搜索**（endpoint 已在 `application.yml` 配置）

```bash
export AI_DASHSCOPE_API_KEY=your_dashscope_api_key
mvn spring-boot:run
```

启动后：

- 官方 ChatUI：http://localhost:8080/chatui/index.html
- 过程可视化（本仓库自研）：http://localhost:8080/process.html

示例研究问题：

- 「调研 Spring AI Alibaba 的核心架构设计」
- 「对比 Claude Code 与 GitHub Copilot 的技术实现」

## 已知局限与 Roadmap

- [ ] **系统化评测集**：当前仅能以墙钟耗时/引用数做粗粒度对照。计划构建 500–800 条标注评测集（每条锚定明文规则条款、含对抗变体、双人标注计算 kappa 一致性）+ 自动评测 runner（FP/FN/Precision/Recall/F1）+ CI 评测门禁（prompt 变更导致指标回退即拦截）。
- [ ] Redis 地址为本地单机硬编码，待配置化；可扩展框架支持的 MySQL/PostgreSQL StateStore。
- [ ] MCP 鉴权依赖环境变量透传，未做多租户密钥管理。

## 致谢与许可

感谢 [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) 团队的官方 DeepResearch 示例（Apache-2.0）。本仓库沿用 Apache-2.0 许可；二次开发部分的版权归本仓库作者。
