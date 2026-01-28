# Copilot / AI 指导说明（针对本仓库 — MyAPS）

目的：让 AI 编码代理能快速理解并安全修改本项目（Spring Boot + Timefold 排班求解示例）。

要点速览
- 架构：Spring Boot 应用，Timefold（OptaPlanner 风格）用于排班求解。REST 层接受求解请求 -> `SolverManager` 管理任务 -> `ConstraintProvider` 定义约束并返回 `HardSoftScore`。
- 主要组件与位置：
  - 控制器：`src/main/java/com/example/demo/controller/SolverController.java`（REST 接口、jobId 缓存、求解生命周期覆盖）
  - 约束：`src/main/java/com/example/demo/constraint/ShiftScheduleConstraintProvider.java`
  - 域模型：`src/main/java/com/example/demo/entity/`（`ShiftSchedule`, `Shift`, `Employee`, `Order`, `TimeGrain`, `Line`）
  - 启动：`src/main/java/com/example/demo/DemoApplication.java`

关键运行 / 开发流程
- Java 版本：使用 JDK 17（项目属性 `java.version=17`）。
- 构建：使用仓库自带的 Maven Wrapper（Windows）

  mvnw.cmd clean package

- 运行：生成的 jar 在 `target/`，可直接运行：

  java -jar target/demo-0.0.1-SNAPSHOT.jar

- 测试：

  mvnw.cmd test

项目约定与重要模式（必须遵守）
- Timefold 要求：保持 `@PlanningSolution` / `@PlanningEntity` / `@PlanningVariable` 注解、无参构造函数、以及 Jackson 可序列化（实体当前使用 Lombok，修改时注意保持兼容）。
- 评分类型：使用 `HardSoftScore`，约束在 `ShiftScheduleConstraintProvider` 中实现（使用 ConstraintFactory 的 join/filter/penalize 模式）。
- `SolverController` 特殊点：控制器在内存缓存 `jobId -> job`（缓存上限常量为 2），并使用 `SolverConfigOverride` 做临时终止条件覆盖，修改此处需谨慎考虑并发与内存。

常用 REST 接口（示例）
- POST /schedules/solve  — 提交求解（若不传 problem，则控制器会创建默认问题）
- GET /schedules/list
- GET /schedules/{jobId}
- GET /schedules/{jobId}/status
- DELETE /schedules/{jobId}

集成点与外部依赖
- 依赖由 `pom.xml` 管理，关键依赖是 `ai.timefold.solver:timefold-solver-spring-boot-starter`（使用 BOM）。
- 前端静态资源在 `src/main/resources/static/`（`index.html`, `app.js`）用于 demo 页面。

编辑/修改建议（只针对本仓库可检验的模式）
- 修改域模型时：保留无参构造、保持 Lombok 注解或替换为显式 getter/setter（避免破坏序列化或 Timefold 要求）。
- 新增/修改约束：在 `ShiftScheduleConstraintProvider` 中实现并为关键约束添加单元测试（可复用现有测试结构）。
- 调整求解参数：优先使用 `SolverConfigOverride`（在 `SolverController`）进行短期试验，不要直接改全局 solver config 除非需要永久修改。

调试与日志
- 控制器使用 Lombok `@Slf4j` 打点求解事件。若需更细粒度日志，编辑 `src/main/resources/application.yaml` 来提升日志级别。

发现与注意事项（仓库特有）
- 不要删除实体类的无参构造函数；Timefold/序列化依赖它们。
- `jobIdToJob` 内存缓存限制（2）是有意的资源保护；若要改为持久化或更大缓存，请评估并发场景。
- 实体类位于 `src/main/java/com/example/demo/entity/`，任何结构性改动会影响 ValueRangeProvider 注入与求解变量范围。

首次上手建议
1. 本地用 JDK 17 + `mvnw.cmd test` 确保测试通过。
2. 通过 `POST /schedules/solve` 提交默认问题，观察日志与 REST 返回的 `jobId`。
3. 修改一个简单约束（例如在 `ShiftScheduleConstraintProvider` 中增加一条软约束），运行测试并用 POST 验证评分变化。

如需我进一步展开：我可以为新的约束生成单元测试骨架，或将 `SolverController` 的 job 缓存改成持久化实现（需要你确认目标存储）。

— 结束 —
# Copilot / AI 指导说明（针对本仓库）

目的：帮助 AI 编码代理快速理解并修改本项目（Spring Boot + Timefold/OptaPlanner 示例）。

要点速览
- 架构：Spring Boot 应用，使用 Timefold（Timefold Solver）进行排班求解；REST 接口负责提交/查询求解任务，约束在单独的 ConstraintProvider 中实现。
- 主要职责划分：
  - 控制器：`src/main/java/com/example/demo/controller/SolverController.java`（REST 接口、任务生命周期、SolverManager 调用）。
  - 约束：`src/main/java/com/example/demo/constraint/ShiftScheduleConstraintProvider.java`（所有 Timefold 约束实现）。
  - 域模型：`src/main/java/com/example/demo/entity/*`（`Employee`, `Shift`, `ShiftSchedule`, `TimeGrain`）。

构建 / 运行 / 测试（项目特定）
- 使用 Maven Wrapper：Windows 下运行 `mvnw.cmd`（位于项目根和 `bin/`），示例：
  - 构建：`mvnw.cmd clean package`
  - 单元测试：`mvnw.cmd test`
  - 跑应用：`java -jar target/demo-0.0.1-SNAPSHOT.jar`（或在 IDE 中直接运行 `DemoApplication`）。
- Java 版本：项目属性中 `java.version`=17（请用 JDK 17 运行/编译）。

项目约定与重要模式（不可忽略）
- 使用 Timefold 注解和约定：`@PlanningSolution`（`ShiftSchedule`）、`@PlanningEntity`（`Shift`）、`@PlanningVariable`、`@ValueRangeProvider`、`@PlanningScore` 等。修改域模型时务必维护这些注解和无参构造函数（Timefold 要求）。
- 评分类型：使用 `HardSoftScore`（硬/软约束），约束实现处请参考 `ShiftScheduleConstraintProvider` 的写法（join/filter/penalize）。
- Lombok：实体使用 Lombok（如 `@Data`），编辑器需要安装 Lombok 支持以避免误报。
- 求解生命周期：`SolverController` 使用 `SolverManager` + `SolutionManager`，并通过 `jobId` 在内存中缓存任务（`jobIdToJob`，缓存上限常量为 2）。修改控制器需注意并发与缓存策略。

REST 接口示例（方便测试）
- 提交求解（若不提供 problem，会使用控制器内置默认问题）：
  - POST /schedules/solve  -> 返回 `jobId`
  - 示例（curl）：
    curl -X POST -H "Content-Type: application/json" http://localhost:8080/schedules/solve
- 列表：GET /schedules/list
- 查询当前方案（含中间结果）：GET /schedules/{jobId}
- 查询状态（轻量）：GET /schedules/{jobId}/status
- 终止求解：DELETE /schedules/{jobId}

代码修改建议（实用、针对性）
- 添加/修改约束：在 `constraint/ShiftScheduleConstraintProvider.java` 中修改或新增方法，遵循现有的 ConstraintFactory -> penalize 模式。
- 修改域模型：更改 `entity` 下类时，保持 `@PlanningEntity`、`@ValueRangeProvider`、无参构造以及序列化（Jackson）兼容性。
- 调整求解行为：`SolverController` 中用 `SolverConfigOverride`（示例：spentLimit、bestScoreLimit）来临时覆盖终止配置；这是测试和快速迭代的推荐方式。

调试与日志
- 控制器使用 Lombok 的 `@Slf4j` 打印求解事件。要深入调试，调整 Spring Boot 日志级别（application.yaml 或运行参数）。

外部依赖与集成点
- 依赖：`ai.timefold.solver:timefold-solver-spring-boot-starter`（以及 BOM 管理）。在 `pom.xml` 中可见 `timefold-solver.version`。
- 与前端/客户端的契约：REST JSON（简单对象图），注意 `TimeGrain` 使用 JSON identity 注解以避免循环引用问题。

注意事项（简短）
- 不要删除实体类的无参构造函数；Timefold/序列化依赖它们。
- 新增复杂约束前，先在单元测试或本地 POST 一个小问题验证评分变更。

如需更多：如果你要修改求解策略或扩展约束，我可以：
- 帮你添加示例请求/集成测试；
- 为新的约束生成单元测试骨架；
- 或者把 `SolverController` 的 job 缓存改为持久化实现（需要说明需求）。

— 结束 —
