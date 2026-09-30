# PatentKing 成果转化智能体 — 总体开发计划

> **For agentic workers:** 按阶段执行；每个 Phase 内任务用 checkbox 跟踪。实现某 Phase 前先确认本 Phase 验收标准已理解。  
> 推荐：每个 Phase 单独开分支 / PR，避免一次混改多子系统。

**Goal:** 在 Apboa Next 智能体框架上，构建「成果转化供需匹配 + 价值评估 + 知识产权保护」一体化平台（PatentKing），并最大化复用已下载的专利 Skills。

**Architecture:** Apboa Next 提供多租户 Console/Runtime/WebSocket、Agent/Skill/Workflow/知识库/看板；PatentKing 以「领域 Agent + Skill 包 + 领域 Workflow + 专利/文献数据层」叠加其上。高研发项（混合精度、跨模态检索、多模态优化）先做产品可交付替代方案，再单独立项深化。

**Tech Stack:** Java 21 / Spring Boot 3.4 / AgentScope · Vue 3 / Ant Design Vue · MySQL / Redis / 向量库 · 既有 patent-skills（handsomestWei / AqooDer / fuyuxiang / paper2patent / PatentRadar）

**仓库现状:**
- 框架：仓库根目录 = Apboa Next（`runner-*` / `biz` / `engine` / `ui` / `workflow`）
- Skills 源码：`patent-skills/`（五套，只读参考 + 打包导入）
- 本计划：`docs/plans/`

---

## 0. 需求拆解 → 能力域（Capability Domains）

| ID | 能力域 | 需求原文映射 | 优先级 |
|----|--------|--------------|--------|
| C1 | 专利撰写与交底流水线 | 创意挖掘、交底书、查新、五书 | P0 |
| C2 | 侵权预警与竞品 claim chart | 竞品布局、侵权预警报告 | P0 |
| C3 | 情报洞察与技术空白 | 现状/趋势、跨学科热点、技术空白 | P1 |
| C4 | 供需智能匹配 | 成果转化供需匹配 | P1 |
| C5 | 专利/项目价值评估 | 组合估值、先进性/稳定性/市场/壁垒报告 | P1 |
| C6 | 尽调与海外转移情报 | 法律状态、事务、尽调/风险评估报告 | P2 |
| C7 | 跨模态与检索增强（研发） | 混合精度、跨模态图像检索、多模态优化 | P2/R&D |

---

## 1. 框架复用地图（Apboa Next）

| Apboa 能力 | PatentKing 用法 |
|------------|-----------------|
| `biz-skill` + Skill Hub | 导入/版本管理专利 Skill 包；Workspace 同步到 Runtime |
| `biz-agent` + ReAct Runtime | 领域智能体：交底官、查新官、侵权分析官、匹配官、估值官 |
| `biz-workflow` + Vue Flow | 固化长流程（挖点→查新→成文；侵权 4 段流水线） |
| `biz-knowledge` + RAG / 向量库 | 专利全文、论文、内部成果库检索 |
| `biz-mcp` / Tool / Hook | 国知局检索、Google Patents、法律状态 API、生图/导出 |
| `biz-dashboard` | 趋势看板、空白地图、匹配结果、估值仪表盘 |
| `biz-gateway` + Automation | 对外 API（评估报告）、定时扫描侵权预警 |
| 多租户 RBAC | 院校/企业/中介多组织隔离 |
| A2A / Agent-as-Tool | 多智能体协作（挖点 Agent 调查新 Agent） |

**本地开发最小集（沿用 README）:**
1. MySQL `apboa_next` + `sql/db_init.sql`
2. Redis
3. `runner-console:3060` → `runner-runtime:3061` → `runner-websocket:3064`
4. `ui` → `pnpm dev`（3030）

---

## 2. Skill 复用决策（五套对照）

| Skill | 复用方式 | 接入能力域 | 改造要点 |
|-------|----------|------------|----------|
| **handsomestWei** | **主 Skill 包**（整包导入） | C1 交底/查新/解读/OA；部分 C6 | 路径改为平台 Workspace；查新脚本容器化；Obsidian 改为平台知识库可选 |
| **AqooDer** | **挖点门禁增强**（prompts 合并或并列 Skill） | C1 专利点清单+商业秘密取舍 | 与 handsomestWei 冲突时：挖点用 AqooDer，类型特化用 handsomestWei |
| **fuyuxiang** | **轻量口述交底模式**（可选 Skill） | C1「从零口述」入口 | 仅保留对话策略 + SVG 脚本；不与主包抢默认路由 |
| **paper2patent** | **论文→五书 Skill** | C1 五书编写 | Flash/Pro 规则进 `references/`；DOCX/PDF 脚本进 Runtime Tool |
| **PatentRadar** | **侵权分析**：优先 Skill 模式；后期可嵌独立服务 | C2 | 公开号门禁保留；4-subagent 在 Apboa 用 Workflow 或 A2A 模拟；搜索 API 配置进租户密钥 |

### 复用原则（必须遵守）

1. **Skill 原文尽量不 fork 大改** — 用「包装层 / 配置 / 适配器」对接 Apboa Workspace 与 Tool。
2. **长流程用 Workflow 编排**，Skill 负责单步专家知识；避免在一个 Agent 里塞 8 步硬编码。
3. **可执行脚本（Python）** → Apboa Tool 或 sidecar 容器（`runner-proxy` 沙箱），不要塞进前端。
4. **产出统一落盘** → 平台 `biz-resource` + 案件目录约定（可参考 AqooDer `vN-时间戳/`）。

---

## 3. 目标产品架构

```
┌─────────────────────────────────────────────────────────────┐
│  UI (Vue)  PatentKing 业务页 + 复用 Apboa Chat/Workflow/Dashboard │
└────────────────────────────┬────────────────────────────────┘
                             │ /api · /api/runtime · /api/ws
┌────────────────────────────▼────────────────────────────────┐
│  runner-console：租户/Agent/Skill/Workflow/知识库/案件 CRUD     │
│  runner-runtime：ReAct + SkillBox + RAG + 领域 Tools           │
│  runner-websocket：流式与进度                                   │
│  （可选）patent-tools sidecar：CNIPA / mermaid / docx / radar   │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│  数据层：MySQL(业务) · Redis · VectorStore · 对象存储(附件/附图) │
│  外部：国知局公布公告 · Google Patents · 学术文献 API · 法律状态 │
└─────────────────────────────────────────────────────────────┘
```

### 领域 Agent 清单（建议）

| Agent | 绑定 Skill / Workflow | 一句话职责 |
|-------|----------------------|------------|
| `pk-disclosure` | handsomestWei A + AqooDer 挖点 | 挖点→查新→交底 |
| `pk-paper2patent` | paper2patent | 论文→五书 |
| `pk-reader` | handsomestWei B | 专利通俗解读入知识库 |
| `pk-oa` | handsomestWei D | 审查答复草稿 |
| `pk-radar` | PatentRadar Skill / Workflow | 侵权竞品报告 |
| `pk-insight` | 自研 prompts + RAG | 趋势/空白/热点 |
| `pk-match` | 自研 + 供需索引 | 供需匹配 |
| `pk-valuate` | 自研 + 法律状态工具 | 价值评估报告 |
| `pk-dd` | 自研 + 法律事务工具 | 尽调/海外转移 |

---

## 4. 关键技术（需求中的「突破项」）落地策略

需求中的「混合精度 / 跨模态图像检索 / 多模态模型优化」属于**算法研发**，不应阻塞产品 MVP。

| 技术点 | MVP（先可交付） | 深化（独立 R&D 里程碑） |
|--------|-----------------|-------------------------|
| 混合精度计算 | 推理侧用现成 API；本地可选量化模型配置 | Runtime 侧接入 vLLM/TensorRT-LLM + FP8/INT8 策略配置页 |
| 跨模态图像检索 | 附图：文本 embedding + 图 embedding（CLIP 类 API）双通道召回；先接开源/云 API | 自研专利附图专用双塔 + hard negative |
| 多模态模型优化 | 交底线稿/附图用现有 skill 生图链路；解读用 PDF 文本为主 | LoRA/蒸馏任务队列 + 评测集 |

**计划原则：** Phase 0–3 不阻塞于自研训练；Phase 4+ 开 `R&D` 专项。

---

## 5. 分阶段路线图（建议 5 个 Phase）

```
Phase 0  基建与跑通 Apboa + Skill 导入          [1–2 周]
Phase 1  C1 撰写流水线（交底/查新/五书）        [3–5 周]
Phase 2  C2 侵权预警（PatentRadar）            [2–4 周]
Phase 3  C3+C4 情报洞察 + 供需匹配             [4–6 周]
Phase 4  C5+C6 估值 + 尽调报告                 [3–5 周]
Phase 5  C7 R&D + 产品打磨/评测/合规           [并行/持续]
```

---

## Phase 0 — 基建与 Skill 导入

**目标:** 本地/Compose 跑通 Apboa；五套 Skill 可被 Console 识别；约定 PatentKing 包名与目录。

### Task 0.1 环境与命名空间

**Files:**
- Create: `docs/dev-environment.md` ✅
- Create: `scripts/pk-dev.sh` ✅
- Create: `docker/patent-tools/`（venv + Dockerfile 骨架）✅

- [x] **Step 1:** 约定隔离策略：Apboa/中间件用 Docker；patent-tools 用专用 `.venv`/sidecar；LLM Key 仅控制台配置
- [x] **Step 2:** 已配置 OrbStack 国内 `registry-mirrors` + `scripts/pk-docker-mirror-pull.sh`；`start-simple.sh build` 成功；Flyway V5 与 `db_init` 重复列已修复（幂等）
- [ ] **Step 3:** 浏览器登录 `admin / Admin@123.com`，打开「模型供应商」页（Key 用户自备）
- [x] **Step 4:** 产品名 PatentKing；模块前缀 `pk-`；UI 先保留 Apboa 壳 + 后续业务菜单

**验收:** 中间件/全栈 healthy；UI 可登录；`curl :3070/healthz` ok。

**进度备注 (2026-08-19):** 国内镜像生效；单机栈已启动（`http://localhost/web/`）；console 已 Started；patent-tools venv `:3070` ok。下一步：浏览器登录并配置 LLM。

### Task 0.2 Skill 包规范化与导入

**Files:**
- Create: `patent-skills/_packaged/`（打包输出，gitignore）
- Create: `scripts/pack-skills.sh`（把各 skill 打成 Apboa 可导入 zip/目录）
- Modify: `.gitignore` 增加 `_packaged/`、`outputs/`、本地密钥

- [x] **Step 1:** 阅读 Apboa Skill 包格式（`biz-skill` + `SkillPackageController` / `InitLoadSkillScript`）
- [x] **Step 2:** `scripts/pack-skills.sh` 产出 `patent-skills/_packaged/patentking-skills.zip`（5 个技能，`skills/<name>/SKILL.md`）
- [ ] **Step 3:** 控制台「技能」上传 ZIP，分类填 `pk`（需登录后操作）
- [ ] **Step 4:** 用最小对话触发 `/交底书` 或 skill name，确认路由命中（允许查新失败，先通路由）

**验收:** ≥3 个 Skill 导入成功且可触发。

**进度备注:** zip 已打好（约 6.7MB）。登录后上传即可。

### Task 0.3 工具 Sidecar 设计（不实现也可先 ADR）

**Files:**
- Create: `docs/architecture/adr-001-matter-and-tools.md` ✅

- [x] **Step 1:** 已拍板：**B2 独立 `patent-tools` sidecar**（非 Runtime 内嵌）
- [x] **Step 2:** 同步拍板案件模型：**A3 新建 `biz-pk-matter`**（非文件夹+标签）
- [x] **Step 3:** sidecar 骨架已有 `/healthz` + `/v1/echo`（venv :3070）

**验收:** ADR 已落地；骨架实现另开 Task。

### 决策锁定摘要（ADR-001）

| 决策 | 结论 |
|------|------|
| 案件域 | 新建 `biz-pk-matter`（`pk_matter` / `pk_matter_version` / `pk_artifact`） |
| 专利工具 | 独立 `patent-tools` sidecar；Agent 经 Tool/MCP 调用 |

---

## Phase 1 — C1 专利撰写流水线（P0）

**目标:** 项目/论文 → 专利点清单 → 查新 → 交底书 / 五书；可导出 Word。

### Task 1.1 案件（Matter）领域模型

**Files:**
- Create: `biz/biz-pk-matter/`（新模块）或先放 `biz/biz-workflow` 扩展表
- Create: 表 `pk_matter` / `pk_matter_version` / `pk_artifact`
- Create: Console Controllers + UI `views/PatentKing/Matter*`

建议字段：`title`, `type(invention|utility|design|paper2patent)`, `status`, `tenant_id`, `workspace_path`, `inventors_json`

- [x] **Step 1:** Flyway `V6__pk_matter.sql`（`pk_matter` / `pk_matter_version` / `pk_artifact`）
- [x] **Step 2:** CRUD API `PkMatterController` + 前端 `views/PatentKing/Matter*` + 侧栏「成果转化 / 案件」
- [x] **Step 3:** 案件详情可绑定默认智能体并「打开对话」（Chat 页）；产物列表占位仍在

**验收:** 可建案件、绑定 `pk-disclosure` 等默认 Agent、打开对话。

### Task 1.2 主交底 Agent + Workflow

**Files:**
- Skill: 导入 handsomestWei + AqooDer 挖点 prompts
- Agent: `pk-disclosure` / `pk-paper2patent` / `pk-radar` / `pk-disclosure-lite`（`POST /pk/bootstrap/agents`）

- [x] **Step 1:** 配置默认 Agent（编码绑定 Skill）；案件类型自动映射
- [x] **Step 2:** Workflow 中「专利点确认」用人工节点 / APIP 表单（Apboa 交互协议）
- [x] **Step 3:** 查新 Tool 对接 sidecar（失败降级 WebSearch，行为对齐 handsomestWei）
- [x] **Step 4:** 案件详情可登记产物写入 `pk_artifact`（md/docx/报告路径或链接）

**验收:** 登录后导入 zip → 案件详情绑定智能体 → 打开对话。完整交底成文仍依赖用户配置 LLM。

**进度备注 (2026-08-19):** 四个默认智能体已绑定硅基流动 DeepSeek-V3.2、系统提示词模板、技能包，以及查新工具 `pk_prior_art_search`。案件详情可登记产物。专利点确认闸门：对话内 APIP 表单 + `pk_confirm_patent_points`。本地栈已补默认 LOCAL 存储（`/app/.apboa/storage`），否则对话传附件会报「存储配置不存在唯一一个有效的配置」。下一步：paper2patent 导出 DOCX Tool。

### Task 1.3 paper2patent 五书

- [x] **Step 1:** 导入 paper2patent Skill；Agent `pk-paper2patent`
- [ ] **Step 2:** Tool 封装 `generate_patent_docx.py` / `export_patent_pdf.py` / drawings
- [ ] **Step 3:** UI：上传论文 PDF → 选 direct / human-in-loop → 下载五书 DOCX

**验收:** 一篇示例论文产出可打开的 DOCX（允许 `【待补充】` 占位）。

### Task 1.4 口述轻量模式（可选）

- [x] 导入 fuyuxiang 为 `pk-disclosure-lite`；案件类型选「口述」时路由到此 Agent

**验收:** 不依赖项目扫描也能生成交底草稿。

---

## Phase 2 — C2 侵权预警（P0）

**目标:** 输入完整公开号 → claim chart 报告；可定时监控。

### Task 2.1 PatentRadar Skill 接入

- [ ] **Step 1:** 导入 `PatentRadar/skills/patentradar`
- [ ] **Step 2:** 用 **Workflow 四段** 映射 4 模块（decompose → competitor_search → full_claim_chart → report），段间 JSON 落 `pk_artifact`
- [ ] **Step 3:** 公开号门禁（无 kind code 拒绝）做成 Console 校验 + Skill 双重
- [ ] **Step 4:** 报告 md 渲染到 UI；PDF 可选

**验收:** 示例公开号产出 `report.md`，含特征对比与证据缺口。

### Task 2.2 预警订阅

- [ ] **Step 1:** 表 `pk_watchlist`（公开号、竞品关键词、cron）
- [ ] **Step 2:** 接 Apboa Automation / Quartz：周期跑轻量搜索，分数≥阈值告警
- [ ] **Step 3:** 通知渠道（站内 + 可选邮件/企微，复用 `biz-channel`）

**验收:** 可添加监控项并手动触发一次扫描。

### Task 2.3 （可选增强）独立 PatentRadar 服务

当 Skill 模式搜索深度不足时，部署 `PatentRadar` Docker，Console 以 MCP/HTTP Tool 调用模式 A。

---

## Phase 3 — C3 情报洞察 + C4 供需匹配（P1）

**目标:** 专利+文献融合检索；聚类/可视化；供需双向匹配。

### Task 3.1 数据摄入管道

**Files:**
- Create: `biz/biz-pk-intel/` 或 ingestion jobs
- Sources: 专利摘要/权要（公开 API 或批量导入）、学术文献（Semantic Scholar / OpenAlex / 用户上传）

- [ ] **Step 1:** 统一文档 schema：`title, abstract, claims_or_fulltext, ipc/cpc, authors, date, modality, tenant_scope`
- [ ] **Step 2:** 写入知识库 + 向量化（复用 `biz-knowledge` / engine RAG）
- [ ] **Step 3:** 附图可选：图向量字段（先云 API CLIP）

**验收:** 可对「某技术领域」做语义检索并返回混合专利+论文结果。

### Task 3.2 洞察 Agent + Dashboard

- [ ] **Step 1:** prompts：技术现状、趋势、竞品布局、跨学科热点、空白点假设
- [ ] **Step 2:** 聚类：IPC + embedding 聚类（先 sklearn/简单服务）；结果缓存
- [ ] **Step 3:** Dashboard 面板：趋势折线、IPC 热力、空白候选列表（复用 `biz-dashboard`）

**验收:** 选一个领域生成「洞察报告」+ 看板可打开。

### Task 3.3 供需匹配

领域模型：`pk_supply`（成果/专利供给）与 `pk_demand`（产业需求）

- [ ] **Step 1:** 供需表单与审核流（租户内）
- [ ] **Step 2:** 匹配算法 V1：向量相似 + IPC 过滤 + 规则加分（地域/成熟度）
- [ ] **Step 3:** Agent `pk-match` 生成匹配说明与推荐动作
- [ ] **Step 4:** UI 匹配结果页 + 一键转「估值/交底」案件

**验收:** 造 10 供 10 需，Top-K 匹配可解释。

---

## Phase 4 — C5 价值评估 + C6 尽调（P1/P2）

**目标:** 多维度评估报告；法律状态整合；尽调包。

### Task 4.1 评估引擎 V1（规则 + LLM 叙事）

维度（与需求对齐）：
1. 技术先进性  
2. 权利稳定性  
3. 市场应用潜力  
4. 竞争壁垒  

- [ ] **Step 1:** 指标采集 Tool：引证、同族、法律状态、剩余年限、诉讼标记（能接多少接多少，缺则占位）
- [ ] **Step 2:** 评分卡配置（租户可调权重）
- [ ] **Step 3:** Agent `pk-valuate` 出报告 + Dashboard 雷达图（VEP/ECharts）

**验收:** 单件专利/组合出 PDF/MD 评估报告。

### Task 4.2 尽调与海外转移包

- [ ] **Step 1:** 复用 handsomestWei 解读 + 法律状态时间线
- [ ] **Step 2:** 模板：尽调清单、风险矩阵、FTO 摘要（声明非法律意见）
- [ ] **Step 3:** 导出尽调压缩包（报告 + 证据索引）

**验收:** 对目标专利生成尽调草稿报告。

---

## Phase 5 — C7 R&D 与产品化打磨

- [ ] 跨模态附图检索评测集与基线（Recall@K）
- [ ] 混合精度推理配置与成本看板
- [ ] 多模态线稿/附图质量人工评分闭环
- [ ] 安全合规：商业秘密标记（AqooDer 取舍）、脱敏、审计日志、免责声明
- [ ] 性能：Runtime 扩容、案件级限流、大 PDF 解析队列
- [ ] 评测：交底质量抽检清单；侵权报告与律师样本对照

---

## 6. 建议目录与模块演进

```
apboa-next/                          # 保持上游可合并
├── patent-skills/                   # 只读上游 skill 源
├── docs/plans/                      # 本计划与分 Phase checklist
├── docs/architecture/               # ADR
├── biz/biz-pk-matter/               # Phase1 新建（建议）
├── biz/biz-pk-intel/                # Phase3
├── biz/biz-pk-valuate/              # Phase4
├── docker/patent-tools/             # sidecar（Phase0/1）
└── ui/src/views/PatentKing/         # 业务前端
```

上游 Apboa 升级策略：尽量少改 `engine` 核心；领域逻辑进 `biz-pk-*` 与 Skill/Workflow。

---

## 7. 里程碑验收（DoD）

| 里程碑 | DoD |
|--------|-----|
| M0 | Apboa 本地跑通 + ≥3 Skill 可触发 |
| M1 | 交底 + 五书样例端到端；Word 可下载 |
| M2 | 公开号 → 侵权报告；可订阅预警 |
| M3 | 领域洞察报告 + 供需匹配 Top-K |
| M4 | 四维估值报告 + 尽调草稿包 |
| M5 | R&D 基线指标上墙 + 合规审计 |

---

## 8. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 国知局反爬/不稳定 | 缓存命中、限速、WebSearch 降级、人工粘贴公开文本 |
| Skill 与 Apboa Workspace 路径不一致 | 包装层统一 `${WORKSPACE}`；CI 冒烟测 |
| PatentRadar 依赖多搜索 Key | Skill 模式先跑通；Key 放租户 SK |
| 需求「算法突破」期望过高 | 产品与 R&D 双轨；合同口径写清 MVP 范围 |
| 法律风险（侵权/评估结论） | UI 强制免责声明；输出标「辅助草稿须人审」 |
| handsomestWei vs AqooDer 流程冲突 | 挖点门禁用 AqooDer；类型成文用 handsomestWei；统一 Workflow |

---

## 9. 立即执行的第一步（本周）

1. **跑通 Phase 0.1**（环境）  
2. **完成 ADR-001**（tools sidecar）  
3. **打包并导入 handsomestWei + paper2patent + PatentRadar skill**  
4. **建第一个 Agent `pk-disclosure` 冒烟**  
5. **确认是否新建 `biz-pk-matter` 还是先用「文件夹+标签」最小案件模型**（建议直接建表，避免返工）

---

## 10. 与「一步一步实现」的协作约定

- 每次只启动 **一个 Phase 内的一个 Task**（或用户点名的 Task）。  
- 完成即更新本文件对应 checkbox，并在 `docs/plans/phase-N-notes.md` 记偏差。  
- 需要改 Apboa 核心时先开 ADR，避免无法跟上游。  

**下一步建议：** 开始 **Phase 0 Task 0.1**（环境跑通）或先确认案件模型 / sidecar 决策。
