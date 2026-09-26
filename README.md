# cc-cargo-loadplan

航班货舱与货物单元管理服务：在重量、舱位和位置限制下生成并确认航班货物配载方案。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run

## 领域模型

- **航班（Flight）**：记录空机重量/力矩、整机重心包线 `[minCg, maxCg]`，以及若干货舱；状态为 `OPEN` / `CLOSED`。
- **货舱（Compartment）**：记录最大重量、最大体积、允许的货物类别集合和站位力臂（用于重心计算）。
- **货物单元（CargoUnit）**：记录重量、体积、类别和不可同舱规则（不允许与哪些类别同舱）；状态为 `AVAILABLE` / `ALLOCATED` / `OFFLOADED`。
- **配载方案（LoadPlan）**：`planNo` 全局唯一，作为幂等键；状态为 `DRAFT` / `CONFIRMED` / `CANCELLED`；保存准备时的配置指纹。
- **卸载事件（OffloadEvent）**：航班关闭后记录的实际卸载事实，仅追加，不修改方案。

## 主要业务规则

1. **独占配载**：一个货物单元同一时间只能属于一个活动方案（草稿或已确认）。方案准备成功即锁定货物；释放（调整移出、卸载）后才可进入其他方案。
2. **确认校验**：确认配载时重新校验全部约束——各货舱重量/体积上限、允许类别、互斥货物不得同舱、整机重心必须落在包线内（`CG = (空机力矩 + Σ货物重量×站位力臂) / 总重量`）。全部校验与货物锁定在同一事务内完成，任一约束不满足即整体回滚，不会留下部分装载状态。
3. **幂等与并发**：`planNo` 是幂等键——相同方案号和相同内容的重复准备/确认直接返回原方案；方案号相同但内容不同则冲突（`PLAN_NO_CONFLICT`）。同一航班的所有变更操作先对航班行加悲观写锁、再按 id 顺序锁定货物单元，并发方案争抢同一货物或剩余舱位时最多一个成功。
4. **过期方案保护**：方案准备时保存配置指纹（航班重心参数 + 全部货舱限制 + 方案内货物的重量/体积/类别/互斥规则的 SHA-256）。准备期间货物重量或航班配置发生变化时，确认会因指纹不一致被拒绝（`STALE_PLAN`）。
5. **调整与卸载（航班关闭前）**：整组调整用新的装载指令集整体替换方案明细，校验通过并提交后才释放原舱位与货物；任何失败整体回滚，原配载不变。也可以从方案中卸载部分货物单元，立即释放舱位与货物锁定。
6. **航班关闭后**：方案不可再修改（准备/确认/调整/卸载均返回 `FLIGHT_CLOSED`），只能追加实际卸载事件，事件将货物标记为 `OFFLOADED`，方案本身保持原状。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/flights` | 创建航班（重心包线、空机参数） |
| POST | `/api/flights/{id}/compartments` | 添加货舱（重量/体积/类别/站位） |
| PATCH | `/api/flights/compartments/{id}` | 修改货舱限制（会使已准备方案过期） |
| POST | `/api/flights/{id}/close` | 关闭航班 |
| GET | `/api/flights/{id}/usage` | 各货舱用量查询 |
| GET | `/api/flights/{id}/constraints` | 约束计算（总重、重心、是否在包线内） |
| POST | `/api/flights/{id}/offload-events` | 关闭后记录实际卸载事件 |
| POST | `/api/cargo-units` | 登记货物单元 |
| PATCH | `/api/cargo-units/{unitNo}` | 修改货物重量/体积（会使已准备方案过期） |
| GET | `/api/cargo-units/{unitNo}/whereabouts` | 货物去向查询（状态、方案、货舱、卸载事件） |
| POST | `/api/load-plans` | 准备配载方案（planNo 幂等） |
| GET | `/api/load-plans/{planNo}` | 方案查询 |
| POST | `/api/load-plans/{planNo}/confirm` | 确认配载（幂等） |
| POST | `/api/load-plans/{planNo}/adjust` | 整组调整 |
| POST | `/api/load-plans/{planNo}/offload` | 关闭前卸载部分货物 |

错误响应统一为 `{"code": "...", "message": "..."}`：资源不存在 404，业务规则冲突 422（`CONSTRAINT_VIOLATION` / `STALE_PLAN` / `FLIGHT_CLOSED` / `CARGO_UNAVAILABLE` / `PLAN_NO_CONFLICT` 等），唯一约束冲突 409。

## 测试

`LoadPlanServiceTest` 覆盖约束校验（超重/超体积/类别/互斥/重心）、幂等、并发争抢（同一货物、剩余舱位各只有一个成功）、过期方案拒绝、调整原子性（失败时原配载不变）、关闭后不可变与卸载事件；`LoadPlanApiTest` 覆盖 HTTP 端到端流程与错误格式。
