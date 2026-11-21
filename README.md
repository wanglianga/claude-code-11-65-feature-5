# 旱灾理赔时点特征服务（Agri Point-in-Time Feature Service）

基于 **Java 21 + Spring Boot 3.5 + PostgreSQL 18** 的可本地运行特征服务，用于农业保险旱灾理赔核验。核心原则只有一条：

> **特征取数以「可用时间」而非「观测时间」为准；历史案件保留评分快照；后到数据只能通过受控回填生成复核候选，绝不直接覆盖旧理赔记录。**

---

## 1. 两套时间语义

仓库中每条记录都同时保存两个时钟：

| 字段 | 含义 |
| --- | --- |
| `observed_at` | 现象实际发生的时间（影像采集、气象时段、查勘/申报时间） |
| `ingested_at` | 平台真正可以看到这条记录的时间（入库时间） |

修订不是 `UPDATE`，而是**追加新版本**并保留 `revision_of_id` 链。取数时统一使用：

```
ingested_at <= availability_ceiling
```

- 原始评分：`availability_ceiling = scoring_cutoff`
- 受控回填：`availability_ceiling = 修订事件入库时间`，且运行模式为 `CONTROLLED_BACKFILL`，**不写快照**，只生成复核候选。

这样，一景 2024-06-18 采集、2024-06-25 才入库的影像，在 2024-06-22 截止的案件中会被明确排除，并留痕在运行台账里。

---

## 2. 功能模块

| 模块 | 说明 | 主要接口 |
| --- | --- | --- |
| 特征目录 | 维护公式、来源类型、可用时滞（`availability_lag_seconds`）、审批版本；已审批版本不可改公式（数据库触发器 + 服务层双重保护） | `POST /api/catalog/features`、`POST /api/catalog/features/{code}/versions`、`POST .../versions/{v}/approve` |
| 时点数据仓 | 影像 / 气象 / 地块边界 / 播种申报的观测时间、入库时间、修订链 | `POST /api/warehouse/scenes|weather|declarations|parcels/{id}/boundaries` |
| 回填编排器 | 按灾害时点生成地块级特征运行，FIFO 队列 + 租约式认领，可同步重放 | `POST /api/cases/{caseNo}/runs`、`POST /api/runs/{id}/execute`、`GET /api/runs/queue/stats` |
| 穿越检测器 | 核验每个输入在评分截止前是否可用，产出持久化报告与逐条违规 | `GET /api/runs/{id}` → `leakage` |
| 评分快照 | 冻结案件引用的特征值与定义版本，数据库层禁止 UPDATE/DELETE | `GET /api/snapshots/{id}`、`GET /api/cases/{caseNo}/snapshots` |
| 影响分析 | 定位一次数据修订影响哪些案件，受控回填并生成复核候选 | `POST /api/impact/analyses/by-revision/{eventId}`、`GET /api/review/candidates` |
| 案件追溯 / 复现 | 案件运行历史、输入台账，一键按原截止时间重放并比对快照 | `GET /api/cases/{caseNo}/trace`、`POST /api/cases/{caseNo}/reproduce` |
| 结案 | 评分完成后结案（`SCORED → PAID`），结案后任何状态回退都被拒绝 | `POST /api/cases/{caseNo}/settle` |
| 影子评分器 | 基于冻结快照对候选定义做双份计算（`SHADOW` 模式），不写快照、不改案件、不产生候选 | `POST /api/governance/shadow/runs`、`GET /api/governance/shadow/runs/{id}` |
| 时序对照规则集 | 校验 `观测时间 ≤ 可用时间 ≤ 评分截止` 与 `定义生效时间 ≤ 评分截止`，阻断穿越 | 影子运行/门禁的 `temporalVerdict` 与 `findings` |
| 反转归因器 | 把分数反转拆解到输入变量、缺失策略、产品（定义）版本三个维度 | 影子比较结果中的 `classification` 与 `attribution` |
| 定义发布门禁 | 仅允许通过阈值与穿越检查的版本投产；审批期间目录变更则门禁作废 | `POST /api/governance/gates`、`POST .../gates/{id}/approve|reject` |
| 定向回退控制器 | 按定义版本（可限案件范围）关闭特征读取；只终止依赖该版本的回填分片 | `POST /api/governance/rollbacks`、`POST .../rollbacks/{id}/revoke` |
| 分片批量回填 | 分片显式声明依赖的定义版本，回退时按依赖精确终止/隔离 | `POST /api/governance/backfill-batches`、`.../backfill-shards/{id}/run` |
| 治理演示 | 影子比较、门禁记录、定向回退三套可脚本化演示 | `POST /api/demo/governance/shadow|gates|rollback` |

Swagger UI：<http://localhost:8080/swagger-ui.html>　OpenAPI JSON：<http://localhost:8080/v3/api-docs>

---

## 3. 三条强制约束的落地方式

### 3.1 晚到影像不得进入原案件评分

`AsOfSelector.selectSatellite` 分两段查询：

1. `ingested_at <= ceiling` → 可用场景；
2. `ingested_at > ceiling` → 写入台账，判定 `EXCLUDED_LATE_AVAILABILITY`，并产生 `LATE_SCENE_EXCLUDED` 告警。

即使采集时间早于事故、云量更低，也不会进入 `ndvi_mean` 等聚合变量。样例中 `SCENE-2024-06-18-B`（云量 3%，NDVI≈0.21）就是这样被挡在 2024-06-22 截止之外的。

若窗口内所有影像都晚到、导致必填特征无法计算，运行状态为 `INSUFFICIENT_DATA` 或 `LEAKAGE_BLOCKED`，案件转 `UNDER_REVIEW`，**不写快照**。实测：一个缺少可用影像的案件运行得到 `BLOCKED`（`NO_ELIGIBLE_SCENE` 告警），快照数为 0。

### 3.2 两个地块边界版本重叠时按案件锁定版本隔离

每个栅格像元（scene cell）通过 `resolveOwner` 归属到**唯一**一个 `(parcel, boundary_version)`：

- 已被理赔案件锁定的版本优先（越小 case id 越优先）；
- 其次 parcel id、版本号最小的版本；
- 其它覆盖同一像元的版本得到 `EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL`，并产生 `PIXEL_SHARED_BETWEEN_FIELDS` 告警。

归属判断只依赖存储数据，与运行先后顺序无关，因此重放可复现。样例中 P-1001 的 v1 与 v2 共享 5 个像元：锁定 v1 的案件拥有它们，边界变体运行（v2）将其全部排除。

> 边界变体运行使用 `REPLAY` 模式 + `boundary_variant = true`，永不覆盖案件快照。

### 3.3 已结案后的气象修订只生成复核候选

`ImpactService.analyze` 对每个有快照的案件判定：

- 修订在截止前已可用 / 快照实际引用了该版本 → `ALREADY_VISIBLE_AT_CUTOFF`
- 观测窗口与该案件 NDVI/降雨窗口不相交 → `NOT_RELEVANT`
- 否则 → 排队 `CONTROLLED_BACKFILL`（`LATEST_AVAILABLE`，天花板=修订入库时间），运行后可生成 `PENDING_REVIEW` 复核候选，包含 `baseline / candidate / delta`。

**冻结快照不写、已付款结论不动**。样例中 WS-001 的 2024-07-08 修订产生候选（`NDVI_DROUGHT` +0.142，`RAIN_ANOMALY` −9.4），快照哈希保持 `26b972d0…` 不变，案件状态仍为 `PAID`。

`review_candidate` 只允许 `PENDING_REVIEW → ACKNOWLEDGED / DISMISSED`，由理赔人员决定，属于流程动作而非自动改单。

案件状态只允许向前：`OPEN/QUEUED → SCORED → PAID`。受控回填、影响分析、复现失败等路径只会把 `OPEN/QUEUED/SCORED` 提升为 `UNDER_REVIEW`，不会把 `PAID` 降级。

---

## 4. 影子优先的发布治理（上线前五件套）

理赔稽核要求：**新定义先在冻结案件快照上做影子评分与时序审查，批准后才能进入正式服务**，而不是直接上线再依赖事后回滚。代价是双份计算、阈值配置和发布等待；收益是异常反转在付款前即可定位，且回退不会波及其它定义与已验证案件。本服务在独立的 `governance` 包与 V2 迁移中落地五件套。

### 4.1 影子评分器（ShadowScorer）

- 对每个有冻结快照的案件，按**原评分截止时间**再跑一次 `mode=SHADOW` 的运行，把目标特征换成候选定义版本，其余特征仍用案件锁定版本（`shadow_run_definition`）。
- 影子运行复用同一套时点取数引擎，但**不写 `scoring_snapshot`、不产生复核候选、不修改 `claim_case` 状态**；队列 worker 也不认领 `SHADOW_SCORING` 运行，避免与同步治理流程竞争。
- 候选结果与不可变快照逐特征比对，落到 `shadow_run` / `shadow_run_feature`。

### 4.2 时序对照规则集（TemporalRuleSet）

对每个影子运行校验三类时钟顺序，失败写入 `temporal_order_finding`：

| 规则 | 要求 | 级别 |
| --- | --- | --- |
| `OBSERVED_BEFORE_INGESTED` | `observed_at ≤ ingested_at` | BLOCKING |
| `INGESTED_BEFORE_CUTOFF` | 被采纳输入 `ingested_at ≤ scoring_cutoff` | BLOCKING |
| `OBSERVED_BEFORE_CUTOFF` | 被采纳输入 `observed_at ≤ scoring_cutoff` | BLOCKING |
| `DEFINITION_EFFECTIVE_BEFORE_CUTOFF` | 定义 `effective_at ≤ scoring_cutoff` | BLOCKING |
| `DEFINITION_VERSION_CHRONOLOGY` | 新版本生效时间不早于其取代版本 | INFO/WARNING |

存在 BLOCKING 即 `temporal_verdict=BLOCKED`、`chain_complete=false`，对应差异判为穿越失败。

### 4.3 反转归因器（ReversalAttributor）

把每个特征的新旧差异拆成三个维度（`attribution.dimensions`）：**输入**（哪些聚合变量变动、来自哪类数据源）、**缺失策略**（必填阻断/可选 NULL、缺失变量清单）、**产品版本**（两侧版本号与公式）。分类规则：

- 链路完整且超阈值 → `BUSINESS_DIFFERENCE`，路由 `RULE_OWNER_REVIEW` 交规则负责人复核；
- 链路不完整（有时序阻断）→ `TIME_TRAVEL_FAILURE`，门禁直接阻断；
- 一侧无法计算（缺失策略差异）→ `MISSING_DATA_DIFFERENCE`；
- 公式无法求值 → `NOT_COMPUTABLE`；其余为 `WITHIN_THRESHOLD`。

### 4.4 定义发布门禁（ReleaseGateService）

- 提交门禁即对冻结快照跑影子活动，聚合反转率、最大差异、时序结论，写入 `release_gate`。
- 仅当 `temporal_passed=true` 且反转率不超过 `reversalRateLimit`（默认 10%）、绝对/相对差异在阈值内（默认 0.15 / 0.20，可用 `app.governance.*` 配置）时，`approve` 才会调用目录审批把版本投产。
- 门禁记录提交时刻的**全目录指纹**（`catalog_fingerprint`，SHA-256）。审批期间只要目录再发生变更（新建版本会发布 `CatalogChangedEvent`），在审门禁立即 `INVALIDATED`；即使绕过事件，审批时也会重新比对指纹并以 `GATE_INVALIDATED` 拒绝——**审批引用的版本一律作废，不能继续投产**。

### 4.5 定向回退控制器（RollbackService）与分片协同

- 回退按「定义版本 + 范围（`ALL_CASES` 或案件清单）」写入 `feature_rollback`，只关闭后续运行对该版本的**特征读取**（`RunExecutor.resolveFeatures` 过滤），冻结快照永不改变；可 `revoke` 恢复读取。
- 批量回填被拆成分片（`backfill_batch` / `backfill_shard`），每个分片在 `depends_on_version_ids` 显式声明依赖的定义版本。
- 回退落地时只处理「未完成且依赖该版本」的分片：已排队但尚无运行的分片置 `QUARANTINED`，已在执行（有运行）的分片终止其 `feature_run` 为 `TERMINATED` 并置 `TERMINATED_BY_ROLLBACK`；**依赖其它定义版本的分片与已成功分片的结果全部保留**，批次最终为 `PARTIALLY_TERMINATED`。

### 4.6 三套演示（一键复现，可重复执行）

```bash
curl -s -XPOST localhost:8080/api/demo/governance/shadow    # 影子比较：BUSINESS_DIFFERENCE 交规则负责人
curl -s -XPOST localhost:8080/api/demo/governance/gates     # 门禁记录：通过/穿越拒绝/目录变更作废
curl -s -XPOST localhost:8080/api/demo/governance/rollback  # 回退：仅终止依赖分片，保留其它版本结果
```

种子数据预置了四个候选 DRAFT 版本覆盖全部归因分类：`NDVI_DROUGHT` v2（业务差异）、v3（生效晚于截止的穿越失败）、`RAIN_ANOMALY` v2（阈值内可投产）、`CROP_EXPOSURE` v2（依赖仓库不存在的变量，缺失策略差异）。

---

## 5. 本地启动

### 5.1 直接运行（使用容器内已装运行时）

前置：PostgreSQL 18 已在 `127.0.0.1:5432`，库 `agri_pit`（用户/密码 `ccdev`）。若没有库，先建：

```bash
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE agri_pit OWNER ccdev;"
```

构建并启动（首次启动自动执行 Flyway 迁移 + 写入演示样例）：

```bash
cd /workspace/65-feature-5
/opt/maven/bin/mvn -q -DskipTests package
/opt/jdk-25/bin/java -jar target/agri-pit.jar
```

如需清空重来：

```bash
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d agri_pit -c "drop schema public cascade; create schema public;"
```

数据库连接可用环境变量覆盖：`DB_URL`、`DB_USER`、`DB_PASSWORD`、`SERVER_PORT`、`APP_SEED_DEMO`。

### 5.2 Docker Compose（一键）

```bash
docker compose up --build
```

`app` 等待 `db` 健康后启动，`8080` 端口对外，样例自动写入。停止并清理：`docker compose down -v`。

> 本仓库交付了 `Dockerfile` 与 `docker-compose.yml`；当前隔离容器内没有 Docker 守护进程，因此本 README 的验证结论均来自 4.1 的直接运行方式。Docker 构建阶段使用 `docker/maven-settings.xml` 指向已配置的 Maven 镜像源。

---

## 6. 测试账号与接口鉴权

本项目为本地演示，**未启用鉴权**，所有接口匿名可访问，无需账号。若需要演示审批人字段，请传 `approvedBy` / `decidedBy`（如 `catalog-admin`、`reviewer`），仅作为审计记录。

---

## 7. 演示样例（启动即写入，幂等）

| 对象 | 内容 |
| --- | --- |
| 特征 | `NDVI_DROUGHT`（NDVI 亏缺，含离散度惩罚）、`RAIN_ANOMALY`（60 天降雨亏缺）、`CROP_EXPOSURE`（申报面积 × 生育期系数） |
| 地块 | `P-1001`（两条边界版本 v1/v2，重叠）、`P-1002`（对照） |
| 气象站 | `WS-001`（主）、`WS-002`（次） |
| 案件 | `DROUGHT-2024-0001`（P-1001，锁定 v1）、`DROUGHT-2024-0002`（P-1002） |
| 灾时点 | 2024-06-20；评分截止 2024-06-22T00:00Z |
| 陷阱数据 | 晚到影像 `SCENE-2024-06-18-B`；晚到气象修订 `WS-001-W2024-06-13` rev 2（2024-07-08 入库） |
| 治理候选版本 | `NDVI_DROUGHT` v2（业务差异）/ v3（穿越失败）、`RAIN_ANOMALY` v2（阈值内）、`CROP_EXPOSURE` v2（缺失策略差异），均为 DRAFT，不影响正式评分 |

`GET /api/demo/scenarios` 会返回场景索引与当前计数；`GET /api/demo/governance` 返回三套治理演示的索引与计数。

---

## 8. 复现流程（验收脚本）

服务启动并可访问后依次执行（建议先跑时点套件，再跑治理套件）：

```bash
./scripts/verify.sh               # 时点正确性：默认 BASE=http://localhost:8080
./scripts/verify-governance.sh    # 影子/门禁/回退治理五件套
```

`verify.sh` 依次验证：

1. **晚到影像**：`DROUGHT-2024-0001` 原始运行含 `EXCLUDED_LATE_AVAILABILITY`，且没有任何 `ingestedAt > cutoff` 的可用输入；
2. **重叠边界**：对 v2 发起边界变体运行，出现 ≥1 条 `EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL`，原快照哈希不变；
3. **气象修订**：先将案件结案为 `PAID`，影响分析给出 `RECOMPUTE_CANDIDATE` 并生成 `PENDING_REVIEW` 候选，案件仍为 `PAID`、仍只有 1 个冻结快照且哈希不变；
4. **复现**：`POST /api/cases/.../reproduce` 按原截止时间重放，`valuesMatch=true`。

`verify-governance.sh` 依次验证：

1. **影子隔离**：每个冻结案件一条影子运行，时序全 PASS、链路完整；超阈值差异判为 `BUSINESS_DIFFERENCE` 并路由规则负责人；案件状态、快照数、复核候选数均不因影子评分而变化；
2. **发布门禁**：阈值/时序通过的版本被 `APPROVED` 投产；生效晚于截止的版本以 `GATE_CHECKS_FAILED` 拒绝；审批期间目录变更使门禁 `INVALIDATED`，再审批返回 `GATE_INVALIDATED`；
3. **定向回退**：批次 `PARTIALLY_TERMINATED`——已成功分片保留、运行中且依赖被回退版本的分片 `TERMINATED_BY_ROLLBACK`（其运行置 `TERMINATED`）、依赖其它定义版本的分片仍 `SUCCEEDED`、排队中的依赖分片 `QUARANTINED`，目标案件冻结快照不变。

手工操作示例：

```bash
# 查看案件与运行
curl -s localhost:8080/api/cases/DROUGHT-2024-0001 | jq .
curl -s localhost:8080/api/cases/DROUGHT-2024-0001/trace | jq .

# 看某次运行的输入台账与穿越报告
curl -s localhost:8080/api/runs/1 | jq '{leakage, inputs}'

# 触发气象修订的影响分析（先结案，模拟已付款）
curl -s -XPOST localhost:8080/api/cases/DROUGHT-2024-0001/settle \
  -H 'Content-Type: application/json' -d '{"settledBy":"claims-officer"}' | jq .
EV=$(curl -s "localhost:8080/api/warehouse/revision-events?sourceType=WEATHER_OBSERVATION" | jq -r '[.[]|select(.revision_no==2)][0].id')
curl -s -XPOST localhost:8080/api/impact/analyses/by-revision/$EV | jq .
curl -s localhost:8080/api/review/candidates | jq .

# 复核决定（只记录人工结论，不改快照/赔付）
curl -s -XPOST localhost:8080/api/review/candidates/1/decisions \
  -H 'Content-Type: application/json' \
  -d '{"action":"ACKNOWLEDGE","decidedBy":"reviewer","note":"accepted for manual adjustment"}'
```

---

## 9. 数据模型要点

- `feature_definition` / `feature_definition_version`：定义与审批版本；`checksum` 记录公式摘要；已审批版本由触发器锁定不可改。
- `claim_case_feature_lock`：案件受理时锁定具体定义版本，之后目录再审批新版本也不影响历史案件。
- `feature_run`：`mode ∈ {ORIGINAL, REPLAY, CONTROLLED_BACKFILL, SHADOW}`，携带 `cutoff_at`、`availability_ceiling`、`boundary_version_id`、`boundary_variant`、`parent_run_id`、`shadow_run_id`。
- `feature_run_input`：输入台账（`observed_at`、`ingested_at`、`eligibility`、`reason`），是穿越检测与追溯的依据。
- `leakage_report` / `leakage_violation`：持久化检测结论与告警。
- `scoring_snapshot` / `snapshot_feature` / `snapshot_input`：冻结快照，`inputs_hash`（SHA-256）用于复现比对；触发器禁止修改。
- `data_revision_event`：所有修订事件，影响分析入口。
- `impact_analysis` / `impact_case` / `review_candidate`：影响范围、受控回填运行、待复核候选。
- `pixel_claim`：像元唯一归属，用于边界版本隔离。
- `feature_definition_version.effective_at`：定义生效时间（第三个时钟），V2 迁移补列并回填为历史审批时间。
- `shadow_run` / `shadow_run_feature` / `shadow_run_definition`：影子活动、逐特征双份比较与归因、候选版本覆盖；影子运行不产出快照。
- `temporal_order_finding`：时序对照规则集的逐条结论（影子运行与门禁共用）。
- `release_gate`：发布门禁记录，含目录指纹、阈值配置、影子运行引用与审批/作废状态。
- `feature_rollback`：定向回退（版本 + 案件范围），只关闭后续读取、不改快照。
- `backfill_batch` / `backfill_shard`：分片回填与每分片的定义依赖集合，是回退精确终止的依据。

公式语言是一个受限表达式解析器（`FormulaExpression`）：支持 `+ - * /`、括号、数字、聚合变量，以及 `min/max/abs/round/floor/ceil/clamp/coalesce`。**不支持任意函数调用**，避免公式越权访问数据。变量只由通过时点筛选的输入填充。

---

## 10. 已验证内容（实际执行结论）

以下结论均为本机实际执行所得：

- `mvn test`：公式解析/求值、时序对照规则集、反转归因器单测全部通过（共 13 个测试方法）。
- `mvn package` 构建成功，`target/agri-pit.jar` 可启动，Flyway 顺利执行 V1+V2 迁移。
- 启动后样例写入成功；两个案件均由队列 worker 执行为 `SUCCEEDED` 并生成快照。
- `./scripts/verify.sh` 全绿：三条时点强制约束 + 复现流程全部通过，治理改造对既有链路零回归。
- `./scripts/verify-governance.sh` 全绿：影子隔离、门禁三态（通过/穿越拒绝/目录变更作废）、定向回退分片协同全部通过。
- 影子隔离实测：影子运行后案件仍为 `SCORED/PAID`、快照数不增加、复核候选数不增加；`feature_run` 中影子运行 `mode=SHADOW` 且无 `result_snapshot_id`。
- 归因实测：链路完整的超阈值差异为 `BUSINESS_DIFFERENCE`（`RULE_OWNER_REVIEW`）；生效晚于截止的定义产生 `DEFINITION_EFFECTIVE_BEFORE_CUTOFF` 阻断并判 `TIME_TRAVEL_FAILURE`；依赖缺失变量的候选判 `MISSING_DATA_DIFFERENCE`。
- 门禁实测：干净门禁 `APPROVED` 后目录 `current_version` 正确切换；穿越门禁审批返回 `GATE_CHECKS_FAILED`；审批期间新建版本使在审门禁 `INVALIDATED`，再审批返回 `GATE_INVALIDATED`。
- 回退实测：四分片批次中运行中的依赖分片被 `TERMINATED`（运行置 `TERMINATED`）、排队分片 `QUARANTINED`、其它定义版本与已成功分片保留，批次 `PARTIALLY_TERMINATED`；回退生效期间普通运行不再读取该特征，`revoke` 后读取恢复；目标案件冻结快照哈希不变。
- `GET /v3/api-docs` 返回 OpenAPI 3.1（55 个路径），`/swagger-ui/index.html` 返回 200，治理端点全部纳入文档。
- 数据库层保护实测：直接 `UPDATE scoring_snapshot` 抛 "scoring snapshots are immutable"；直接改已审批公式抛 "approved feature definition versions are immutable"。
- 状态保护实测：案件结案为 `PAID` 后执行影响分析、影子评分和复现，状态保持 `PAID`，快照数与哈希不变。
- 演示幂等实测：重复调用三个治理演示端点均返回 200，已被消费的 DRAFT 版本对应步骤安全跳过。

未验证项：当前容器无 Docker 守护进程，`docker compose up` 未实际执行；已提供可构建的 `Dockerfile` / `docker-compose.yml`。

---

## 11. 目录结构

```
src/main/java/com/agri/pit/
  catalog/      特征目录、公式表达式、审批、目录变更事件
  ingest/       时点数据仓写入与修订事件
  cases/        案件受理、版本锁定
  runs/         运行队列、执行器（含影子覆盖与回退过滤）、特征求值、worker
  leakage/      时点取数、像元归属、穿越检测
  snapshots/    快照冻结、回填候选、复现比对
  impact/       修订影响分析、复核候选
  governance/   影子评分器、时序规则集、反转归因器、发布门禁、定向回退、分片回填、治理演示
  demo/         确定性样例与场景索引
src/main/resources/db/migration/V1__schema.sql
src/main/resources/db/migration/V2__governance_shadow_gate_rollback.sql
scripts/verify.sh                # 时点正确性验收
scripts/verify-governance.sh     # 治理五件套验收
Dockerfile / docker-compose.yml
```
