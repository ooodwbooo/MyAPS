# MyAPS — 订单排班求解示例（Spring Boot + Timefold）

简要说明
--
MyAPS 是一个使用 Spring Boot + Timefold（类似 OptaPlanner）实现的订单排班求解示例项目。后端通过 REST 接口接收求解请求，使用 `SolverManager` 管理异步求解任务，约束在 `ConstraintProvider` 中定义并返回 `HardMediumSoftScore`。

先决条件
--
- JDK 17
- Maven（推荐使用仓库自带的 Maven Wrapper）

构建与运行（Windows）
--
构建：

```bat
mvnw.cmd clean package
```

运行：

```bash
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

测试：

```bat
mvnw.cmd test
```

主要目录与关键文件
--
- `src/main/java/com/example/demo/controller/SolverController.java`：REST 接口、`jobId` 内存缓存、使用 `SolverConfigOverride` 临时覆盖求解终止条件。
- `src/main/java/com/example/demo/constraint/ShiftScheduleConstraintProvider.java`：所有 Timefold 约束实现示例（join/filter/penalize 风格）。
- `src/main/java/com/example/demo/entity/`：域模型（`ShiftSchedule`, `Shift`, `Employee`, `Order`, `TimeGrain`, `Line`），保留无参构造与 Lombok 注解以保持序列化兼容性。
- `src/main/resources/application.yaml`：日志与应用配置。
- `src/main/resources/static/`：演示前端（`index.html`, `app.js`）。

常用 REST 接口
--
- `POST /schedules/solve` — 提交求解（若不传 problem，控制器会创建默认问题）。
- `GET /schedules/list` — 列出当前 job 简要信息。
- `GET /schedules/{jobId}` — 获取（可能是中间的）解。
- `GET /schedules/{jobId}/status` — 轻量的状态查询。
- `DELETE /schedules/{jobId}` — 终止并移除 job。

示例：提交求解（curl）

```bash
curl -X POST -H "Content-Type: application/json" http://localhost:8080/schedules/solve
```

前端演示页面（`src/main/resources/static/`）
--
项目自带一个简单的前端用于可视化与交互，文件位于 `src/main/resources/static/`：

- `index.html`：页面骨架，标题为“排程可视化（产线 / 员工）”。
- `app.js`：用于与后端交互并渲染甘特图与分析面板的脚本。
- `styles.css`：页面样式。

主要功能与行为（由 `app.js` 实现）：
- 入口：在浏览器中打开 `http://localhost:8080/` 即可访问演示页面。
- 开始/停止求解：`开始求解` 按钮会通过 `POST /schedules/solve` 启动求解，返回 `jobId`（文本）。`停止求解` 会调用 `DELETE /schedules/{jobId}`。停止按钮仅在后端处于求解中时可用。
- 作业选择与轮询：页面会调用 `GET /schedules/list` 填充作业下拉；选中作业后通过 `GET /schedules/{jobId}` 轮询最新方案（轮询间隔可选）。
- 刷新策略：提供 `onlyWhenSolving`（仅在求解时轮询）与 `always` 两种策略。
- 渲染稳定性：为避免短时抖动，前端实现了稳定性检测（需要连续 N 次相同变更后才渲染），因此短时间的快速更新可能不会立即刷新视图。
- 视角与缩放：支持 `产线视角` / `员工视角` 切换，甘特图支持缩放（通过缩放按钮调整像素/分钟比例）。
- 结果分析：页面在 `结果分析` 区块展示统计摘要，并向 `PUT /schedules/analyze` 发送当前方案以获取详细的约束分析（如果后端实现了该端点）。
- Tooltip：鼠标悬停在订单条上可以查看订单详情（员工、产线、时间窗口、工时等）。

修改注意事项
--
- 若后端 API 的返回字段名或结构发生变化（例如 `orders`、`employees`、`dateTimes` 或 `score` 字段），请同步更新 `app.js` 中的解析与渲染逻辑（`computeScheduleHash`、`renderGantt`、`renderAnalysis` 等函数对字段名有依赖）。
- 前端会调用 `/schedules/analyze`（PUT）来获取约束分析结果；如果后端未实现该接口，页面会显示“Score analysis unavailable”。


项目特有约定与注意事项
--
- Timefold 要求：保持 `@PlanningSolution` / `@PlanningEntity` / `@PlanningVariable` 注解以及实体的无参构造函数；修改实体请确保 Jackson/Lombok 兼容。
- 评分类型：使用 `HardMediumSoftScore`，约束在 `ShiftScheduleConstraintProvider` 中实现。
- `SolverController` 的 `jobIdToJob` 内存缓存上限为 2（资源保护）。如需扩展为持久化缓存，请注意并发与生命周期管理。

开发建议与修改指引
--
- 修改域模型：保留无参构造、兼容 Jackson，或显式添加 getter/setter。
- 添加/修改约束：在 `ShiftScheduleConstraintProvider` 中实现并为关键约束添加单元测试。
- 调整求解参数：优先使用 `SolverConfigOverride`（`SolverController` 中）进行短期试验，不要直接改全局 solver config。

调试与日志
--
- 控制器使用 Lombok `@Slf4j` 打点求解事件；需要更细粒度日志请编辑 `src/main/resources/application.yaml`。

下一步建议
--
- 若你要新增约束，我可以生成单元测试骨架并实现一条示例软约束以演示评分变化。
- 若你要将 `jobId` 缓存持久化，请说明目标存储（Redis/数据库），我可协助改造。

贡献者
--
请在本仓库中提交 PR，描述变更与理由，单元测试优先。
