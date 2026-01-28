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
## Copilot / AI 指南 — MyAPS (精简版)

目的：让 AI 编码代理能快速上手并安全修改本仓库（Spring Boot + Timefold 排班求解示例）。

要点速览
- 架构：Spring Boot 应用；Timefold 负责求解（约束在 `ConstraintProvider`），REST 控制器接收求解请求并通过 `SolverManager` 管理求解生命周期。
- 关键文件位置：
  - 控制器：[src/main/java/com/example/demo/controller/SolverController.java](src/main/java/com/example/demo/controller/SolverController.java#L1) — job 缓存、`SolverConfigOverride` 使用点
  - 约束：[src/main/java/com/example/demo/constraint/ShiftScheduleConstraintProvider.java](src/main/java/com/example/demo/constraint/ShiftScheduleConstraintProvider.java#L1)
  - 域模型：目录 [src/main/java/com/example/demo/entity/](src/main/java/com/example/demo/entity/)
  - 启动：[src/main/java/com/example/demo/DemoApplication.java](src/main/java/com/example/demo/DemoApplication.java#L1)

构建 / 运行 / 测试（快速参考）
- Java: JDK 17（项目 property `java.version=17`）。
- 构建/测试（Windows）：
  - `mvnw.cmd clean package`
  - `mvnw.cmd test`
  - 运行 jar：`java -jar target/demo-0.0.1-SNAPSHOT.jar`

核心约定（必须遵守）
- Timefold 要求：保留 `@PlanningSolution` / `@PlanningEntity` / `@PlanningVariable` / `@ValueRangeProvider` 注解以及实体的无参构造函数（Lombok 正常保留即可）。
- 评分使用 `HardSoftScore`；约束一般用 ConstraintFactory 的 join/filter/penalize 模式实现（参见 `ShiftScheduleConstraintProvider`）。

重要实现/修改指引（项目特有）
- 求解参数与短期试验：优先通过 `SolverConfigOverride`（在 `SolverController`）临时覆盖终止条件（`spentLimit`, `bestScoreLimit` 等），而非修改全局 solver config。
- 任务缓存：`SolverController` 在内存维护 `jobId -> job` 映射（缓存上限为 2）。任何更改请评估并发、内存和持久化需求。
- 更改实体：保持无参构造与 Jackson 可序列化；若移除 Lombok，请确保等价的 getter/setter/构造存在以免破坏序列化或 Timefold。

REST 快速示例
- 提交求解：`POST /schedules/solve`（若 body 为空，控制器会生成默认问题）。
- 列表/状态：`GET /schedules/list`, `GET /schedules/{jobId}`, `GET /schedules/{jobId}/status`。
- 终止求解：`DELETE /schedules/{jobId}`。
- curl 示例：
  - `curl -X POST -H "Content-Type: application/json" http://localhost:8080/schedules/solve`

调试与日志
- 控制器使用 Lombok `@Slf4j` 打点求解事件；调整日志级别请编辑 [src/main/resources/application.yaml](src/main/resources/application.yaml#L1)。

外部依赖与集成点
- 关键依赖在 `pom.xml`，主要是 `ai.timefold.solver:timefold-solver-spring-boot-starter`（BOM 管理）。
- 前端 demo 在 `src/main/resources/static/`（`index.html`, `app.js`）。

注意事项（不可忽视）
- 保留实体无参构造函数（Timefold + Jackson 依赖）。
- 在添加复杂约束前，用小样本问题通过 `mvnw.cmd test` 和 `POST /schedules/solve` 验证评分影响。

想要我帮忙？
- 我可以：生成约束的单元测试骨架、示例请求、或把 `SolverController` 的 job 缓存改为持久化实现（需说明后端存储目标）。

— 结束
