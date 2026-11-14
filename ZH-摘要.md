# Ghost Map 立项摘要（中文，一页）

**一句话：** 给 AI 编程 Agent 的 Java/Spring 代码地图。每条调用边都带“谁看到的”（来源）和“有多可信”（置信度）。看不清的地方显式标成“幽灵边”，不装懂。
**赛道：** Kiroween 2026 · Frankenstein（把语法树、字节码、Spring 装配、JVM 运行时探针四种技术“缝”在一起）。
**截止：** 提交 12/4 14:00 PT（北京 12/5 06:00），内部目标提前 2 小时；积分申请截止 12/1 12:00 PT（北京 12/2 04:00）。

## 做什么
- **四层证据：** ① tree-sitter 源码 AST ② ASM 读 class 字节码（补上 Lombok、生成代码、继承和真实 invoke 目标）③ Spring 装配（XML ref/property、parent 继承、注解注入）④ Java agent 运行时探针（记录真实调用，用来校准静态边）。
- **join policy（规则 R01–R10）：** 把四层合成一张图。置信度用 noisy-OR 合成，保证“加证据不会降分”，并用属性测试证明。
- **盲区报告：** 统计未解析点（getBean、反射、字符串注册表）、幽灵边（只在运行时出现的调用），以及“已执行代码上的静态召回率”。只报实测范围，不外推。
- **交易卡：** 入口 → controller → service → DAO/DB/外部调用。每一跳带 文件:行号、来源和置信度，节点挂外部调用、配置、事务、分支等切面，并记录代码版本。
- **MCP 服务**（10 个只读工具）+ 鬼屋风格 Web UI。UI 用静态站部署，即比赛要求的可访问 URL。
- **演示核心：** “纯 AST vs Ghost Map 漏边对比”。数据只来自基准脚本实测，不预设数字。

## 推荐技术栈（及理由）
| 部分 | 选择 | 理由 |
|---|---|---|
| 核心引擎、CLI、MCP | Java 25 (LTS) + Gradle | 字节码（ASM）和 Spring 解析在 JVM 上最自然。官方 tree-sitter Java 绑定需要 JDK 23+，有官方 MCP Java SDK。全栈一种语言，评委看技术实现也更连贯 |
| 运行时探针 | Java agent，以 `--release 17` 编译，Byte Buddy | 能挂到老版本 JDK 的目标应用上。JFR 只做可选补充，因为它是采样，记不全调用边 |
| Spring 解析 | 自写：StAX 解析 XML，ASM 读注解 | 不启动目标应用，只读分析，安全可控 |
| Web UI | TypeScript + Vite + React + Cytoscape.js，部署 GitHub Pages 静态站 | 零后端，最稳定，满足“working app URL” |
| 测试 | jqwik（Java 属性测试）+ fast-check（TS） | 正好对应比赛看重的 property-based testing |

## 对比结论（已查官网，诚实版）
Aider repo map 做的是上下文筛选。SCIP/LSIF 是编译器级的符号索引。CodeQL 是安全向的查询引擎，许可证有限制。OpenLineage 管数据血缘，领域不同，我们借了它的 facet 思想。
**最接近的是 Code-Graph-RAG**：它已经有 tree-sitter、可选 javac 前端、运行时 trace 和 MCP。所以我们的差异点要讲准，主打 JVM/Spring 的深度：字节码层、Spring 装配层（含 XML parent）、逐边置信度与来源、显式幽灵边 + 盲区报告、交易卡。

## 合规红线
代码全部在比赛期内从零写，不碰行内代码、内部框架名或结构。演示只用 spring-petclinic、spring-framework-petclinic（XML 配置，Apache-2.0），外加我们自己写的合成样例 haunted-house。

## 需要你拍板的事
1. **GitHub：** 用哪个账号、仓库叫什么名字？我这边需要连上 GitHub 才能建公开仓库。
2. **许可证：** 推荐 Apache-2.0（自带专利条款，Java 生态常用），也可以选 MIT。
3. **Kiro 怎么用：** 在你电脑上装 Kiro IDE（需要你同意），还是开付费 Web 计划？积分要尽早申请，你本人需要完成 Didit 人脸 + 证件核验。
4. **范围取舍：** 运行时探针（第 4 层）风险最大，进度落后时是否允许降级成“只用 JFR 采样”或推到最后做？
5. **“既有代码”披露怎么写：** 建议写“思路来自本人过往经验，无既有代码”。另外是否用真名公开、是否参加博客 / 社媒加分项？

文件位置：`/workspace/ghostmap/`（README、.kiro/steering、.kiro/specs/ghost-map-core、.kiro/hooks、docs/）。
