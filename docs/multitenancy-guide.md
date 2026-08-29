# 多租户使用手册

> 面向脚手架使用者的操作手册：怎么做、什么不能做、做错了是什么现象。
> 技术事实以仓库当前实现为准。规则层在 common（纯函数、存储无关）：`TenantWriteGate` /
> `TenantScopeExitHandler`；载体层在 server：`TenantContextAccessor` / `TenantPrivilege` /
> `ThreadContextTenantIdentifierResolver` / `TenantRepositoryAspect` /
> `JpaTenantScopeExitHandler` / `TenantReadIsolationAdapter`。

---

## 1. 核心模型

### 1.1 三类数据表

| 类别 | 落地方式 | 隔离行为 |
|------|----------|----------|
| **Global** | 继承 `BasePO` | 无 `tenant_id` 列，任何上下文可读写，不参与过滤 |
| **Tenant-scoped** | 继承 `TenantScopedBasePO` | 带 `@TenantId tenantID` 列，读写自动隔离 |
| **System-level** | 无独立基类，由项目自定 | 平台级数据（如平台配置、租户目录）。可建为 Global 表；
  若建为 tenant-scoped 表则仅应在提权作用域内访问（见 §4） |

选型规则：一条数据「是否天然属于某个租户」——是则 tenant-scoped，否则 Global。
拿不准时选 tenant-scoped（默认安全），事后放开比事后补救容易。

### 1.2 身份三元组 ≠ 提权状态

这是两套互不相干的机制，混为一谈是绝大多数误用的根源：

| | 身份三元组 | 提权/读放行状态 |
|---|-----------|----------|
| 内容 | `tenantID` / `role` / `identity` | 是否处于提权 / 读放行作用域（布尔） |
| 载体 | `ThreadContext`（request-scoped bean）+
  异步快照 `ContextSnapshot` | `TenantPrivilege` 内的两个 `ScopedValue<Boolean>`
  （`ELEVATED` / `READ_BYPASS`） |
| 跟随谁 | **随人走**：请求带着它流转，
  异步经快照传播到 worker | **跟代码位置走**：仅在 `elevated { ... }` /
  `withReadBypass(...)`（`@CrossTenant`）块内为真，出块即恢复，不随线程传播 |

身份回答「我是谁、我在哪个租户」；提权/读放行回答「这段代码此刻是否被允许跨租户」。
二者都不会改变你的租户身份，也不会传播给异步任务。

### 1.3 模型语言：视角、两档授权与不变量

本文档以「视角」「授权」「不变量」描述多租户行为（模型提取自存储领域模型，与 PostgreSQL RLS
对照验证）：

- **视角（View）**：数据可见范围的推导——**当前租户视角**（默认，只见本租户数据）与
  **全量视角**（跨租户可见）。视角是纯函数：由授权状态推导，不存储；
- **两档授权**：**提权**（`elevated`，读写两用）与**读放行**（`withReadBypass` /
  `@CrossTenant`，只读）。提权是写门禁的唯一判断源；读放行只关闭读过滤；
- **访问点**：一切数据操作的入口（现状 = Spring Data repository 代理 + Hibernate filter）。

**不变量（I1-I5）**——本手册所有规则都是它们的落地：

| # | 不变量 | 含义 |
|---|--------|------|
| I1 | 归属不可变 | 数据的租户归属一旦落库不可经实体修改 |
| I2 | 缓存与视角一致 | 视角切换后，数据层缓存不得滞留异视角实体（作用域退出 flush+clear，见 §4.5 ⑤） |
| I3 | 写目标合法性 | 任何写操作的目标行必须属于当前视角允许的范围 |
| I4 | 插入归属必得 | 新插入的数据必须带归属：非提权由视角补全（② 注入），提权下必须显式声明（④ 拒绝空归属） |
| I5 | 视角缺失 fail-closed | 上下文租户缺失时不放行 tenant-scoped 数据（读空集、写拒绝） |

---

## 2. 读隔离原理与定型语义

### 2.1 resolver 只在 session 打开时调用一次

读隔离基于 Hibernate discriminator multi-tenancy（`@TenantId`）。底层是一个命名 filter
`_tenantId`（条件 `tenant_id = :tenantId`），参数来自 resolver：

```java
// ThreadContextTenantIdentifierResolver —— session 打开的瞬间调用一次
public String resolveCurrentTenantIdentifier() {
    if (tenantPrivilege.isAnyReadBypassActive()) {
        return ROOT_TENANT_ID;          // 提权或读放行 → root 视角，不启用过滤
    }
    return tenantContextAccessor.getTenantIDOrMissing();  // 缺失 → __MISSING_TENANT__（fail-closed）
}
```

返回值**绑定该 session 终身**。由此推出两条铁律：

1. **写入模式终身定型**：session 打开时定型的租户是 Hibernate `@TenantId` assigned-id 校验
   （写入侧判定）的基准，之后本事务内不可更改。
2. **读取视角每次访问前自查**：读过滤不受 session 定型束缚——每次 repository 访问前由
   数据访问点按当前状态（普通 / 提权 / 读放行）重新启停 filter（见 §2.2），逐访问生效。

需要另一个**写入**视角 = 新 session：跨租户写必须让提权作用域罩住事务边界（§2.3）。

### 2.2 读路径：数据访问点自查（双保险合流）

读路径有两重保险，`elevated` 与 `@CrossTenant` 都不必强制罩住事务边界：

- **第一重（session 打开时）**：resolver 自查到任一读放行状态（提权或读放行）即返回
  root 租户，新 session 不启用 `_tenantId` filter——作用域内新建的短命查询天然全量可见；
- **第二重（每次数据访问时）**：`TenantRepositoryAspect` 在每个 Spring Data Repository
  调用前先执行 `TenantReadIsolationAdapter.applyReadIsolation()`（自查模式）——线程已绑定
  EntityManager 时按当前状态开关 filter：任一读放行激活 → `disableFilter`；否则 →
  `enableFilter` + `setParameter(当前租户)`。即使先以普通租户开了事务、session 已定型，
  作用域内的查询也会在**下一次访问时**透传；作用域退出后下一次访问自动恢复过滤。

```java
// ✅ 读：已定型 session 内提权查询也放行（第二重保险生效）
@Transactional
public List<Order> listAllOrders() {
    return tenantPrivilege.elevated(() -> orderRepository.findAll());   // 全量可见
}

// ✅ 更简洁的正确姿势：组合 API，先提权再开事务
public List<Order> listAllOrders() throws Exception {
    return tenantPrivilege.elevatedInTransaction(transactionTemplate,
            () -> orderRepository.findAll());
}
```

读视角的切换**逐访问生效、退出即恢复**：同一事务内可以先普通查询 → 提权全量查询 → 普通
查询，三次访问各自按当时状态过滤（证例：`sameTransactionElevatedScopeShouldExposeAllTenantsAndRestoreAfterExit`）。
自查只作用于**读** filter，写路径不受影响（§2.3）。

### 2.3 写路径：提权仍然必须罩住事务边界

写路径不享受数据访问点自查的放行：Hibernate `@TenantId` 的 assigned-id 校验基于
**session 定型时**的租户，与提权状态无关。已定型 session 内提权写入显式异租户实体仍会在 flush 时抛
`PropertyValueException`（fail-fast，设计如此）。

```java
// ❌ 错误：session 已按本租户定型，写异租户实体必然 flush 失败
@Transactional
public void createCrossShopOrder() {
    tenantPrivilege.elevated(() -> shopRepository.save(crossShopOrder));   // 抛 PropertyValueException
}

// ✅ 正确：先提权再开事务，session 打开时租户模式即按提权定型
public void createCrossShopOrder() throws Exception {
    tenantPrivilege.elevatedInTransaction(transactionTemplate,
            () -> shopRepository.save(crossShopOrder));
}
```

无事务的短命查询（每次 repository 调用各开一个 session）同样覆盖：只要调用发生在作用域内，
新 session 打开时 resolver 即可自查到读放行状态（第一重保险）。证例见
`server/src/test/java/com/nona/inf/persistence/tenant/TenantRepositoryAspectTest.java`
的 `elevatedScopeShouldExposeAllTenantsInsideAndRestoreIsolationAfterExit` 与
`sameTransactionElevatedScopeShouldExposeAllTenantsAndRestoreAfterExit`。

---

## 3. 写入门禁

写门禁是**两条件判定**（提权状态 × 实体归属），与操作方法名无关（删除路径同样受门禁保护；方法名匹配已删除）。规则由 common 模块的纯函数 `TenantWriteGate#decideInjection` 承载——存储无关、
零 Spring/JPA 依赖（grep 门）；`TenantRepositoryAspect` 只是载体：遍历方法参数中的租户实体 →
调用判定 → 按返回值执行注入。

### 3.1 判定模型与注入值语义

判定输入只有三个值：`ownedTenantId`（实体归属）、`contextTenant`（当前视角租户）、
`elevated`（是否提权，判断源 `TenantPrivilege.isActive()`）。返回值 = **需注入的租户值**，
`null` = 放行（不写入实体）；拒绝 = 抛 `BusinessException`（fail-fast）：

| 提权 | 实体归属 | 当前视角租户 | 判定结果 | 不变量 |
|------|---------|-------------|----------|--------|
| 否 | 空（null/空白） | 有 | **② 注入** `contextTenant` | I4 视角补全 |
| 否 | = 当前租户 | 有 | 放行 | — |
| 否 | ≠ 当前租户 | 有 | **① 拒绝**（`cross-tenant write is forbidden. currentTenant=..., entityTenant=...`） | I3 写目标合法性 |
| 否 | 任意 | 缺失（null/空白） | **拒绝**（`tenantID is required for tenant-scoped write`） | I5 视角缺失 fail-closed |
| 是 | 空（null/空白） | 任意 | **④ 拒绝**（`elevated write requires explicit tenantId but tenant is missing or blank`） | I4 插入归属必得 |
| 是 | 显式合法租户 | 任意 | 放行并保留原值（不注入） | I1 归属不写入 |
| 任意 | 哨兵值（`__MISSING_TENANT__` / `__ROOT_TENANT__`） | 任意 | **拒绝**（`invalid tenantId cannot be used as entity tenant`） | 防污染（与 minor-1 同源） |

要点：

- **④（修订）**：提权 + 实体空归属 → **拒绝，不再注入**。归属不可发明——「谁做的」是
  identity 职责，非租户职责。空白归属在判定前归一为缺失
  （`normalizeTenantID`），故提权 + 空白同样拒绝；
- **哨兵**：`__MISSING_TENANT__`（视角缺失占位）与 `__ROOT_TENANT__`（全量视角）是框架内部
  值，**不可作为实体归属**——判定前置拒绝，提权/非提权统一语义；
- **② 注入只发生在「非提权 + 归属缺失」**：这是唯一的「框架替你写归属」分支。

非提权代码示例（旧「三条规则」的模型语言形态）：

```java
// ② 实体无租户 → 注入当前租户
po.setTenantID(null); repo.save(po);          // ✅ 落库为当前租户

// 实体显式租户一致 → 放行
po.setTenantID("t1"); /* 当前 t1 */ repo.save(po);   // ✅

// ① 实体显式租户不一致 → 拒绝
po.setTenantID("t2"); /* 当前 t1 */ repo.save(po);
// ❌ BusinessException: cross-tenant write is forbidden. currentTenant=t1, entityTenant=t2
```

前置约束：**当前视角必须有租户**（I5）。`threadContext.setTenantID(null)` 或空白时保存直接抛
`BusinessException: tenantID is required for tenant-scoped write`。

### 3.2 写操作形态分类：判定与参数有关，与操作方法名无关

门禁只问一个问题：**方法参数里能取到租户实体吗？**（R2 收口）

| 形态 | 操作 | 防线 |
|------|------|------|
| **PO 形态**（参数是 `TenantScopedBasePO`，或 Iterable 内的 PO 元素） | `save` / `saveAndFlush`、`saveAll` / `saveAllAndFlush`（集合）、`delete(entity)` / `deleteAll(集合)` / `deleteAllInBatch(集合)` | **门禁判定**（逐实体两条件判定） |
| **ID/无参形态**（参数取不到租户信息） | `deleteById(id)` / `deleteAllById(ids)` / `deleteAll()` / `deleteAllInBatch()` | **JPA filter 兜底**（I3 在目标解析阶段达成，H3 契约见 §3.5） |

要点：

- 旧实现按方法名匹配（仅 save 系列），删除路径不经门禁——已由参数判定取代；
- **带实体删除**：带实体的删除受门禁判定——注解读放行内删异租户实体照常被拒（§4.5 ⑥）；
- **读方法参数**：带租户实体参数的方法（**含读方法**）都会被判定——读方法参数勿带租户实体（§4.5 ⑥）；
- ID/无参形态的 I3 由 Hibernate discriminator filter 达成：删除前先 SELECT 过滤，异租户行
  load 不到 → 删不掉。**这是 Hibernate 行为契约（实验 D 转正），升级须回归**（§3.5 H3）。

### 3.3 提权作用域内：④ 空归属拒绝，显式归属放行

```java
// ✅ 实体显式合法租户 → 放行并保留原值（上下文有没有租户都行）
tenantPrivilege.elevated(() -> {
    TestTenantNotePO po = new TestTenantNotePO();
    po.setId(100L);
    po.setTenantID("shop-B");        // 显式声明归属
    noteRepository.save(po);         // 落库为 shop-B
});

// ❌ ④ 提权 + 空归属（null/空白）→ 拒绝，不再注入（fail-closed）
tenantPrivilege.elevated(() -> {
    TestTenantNotePO po = new TestTenantNotePO();   // tenantID 为 null
    noteRepository.save(po);
    // BusinessException: elevated write requires explicit tenantId but tenant is missing or blank
});

// ❌ 实体携带哨兵值（__MISSING_TENANT__ / __ROOT_TENANT__）→ 拒绝（哨兵不是合法租户）
```

提权不是免检通道：**每一行数据的归属都必须显式写在实体上**。判定矩阵全表见
`common/src/test/java/com/nona/tenant/TenantWriteGateTest.java`（14 条纯函数单测）与
`TenantRepositoryAspectTest`（`crossTenantWrite*` 系列 + `elevatedWriteWithNullTenantShouldFail` /
`elevatedWriteWithBlankTenantShouldFail` 集成证例）。

### 3.4 flush 落库值

insert 时 Hibernate 写侧校验（`@TenantId` assigned-id）与门禁对齐：session 租户为 root
（提权下新建 session）时保留实体的显式 `tenantID`；普通租户 session 下显式值 ≠ session 租户
会抛 `PropertyValueException`。因此**不要绕过 repository 直接 `EntityManager.persist`
写 tenant-scoped 实体**——那会跳过 AOP 门禁的友好报错，直接撞上 ORM 层的异常（§4.5 ④ 红线）。

### 3.5 JPA 实现偏差（Hibernate 行为契约）

机制层行为与模型语言的偏差收敛在 JPA 适配层，以下为**契约**（不收缩框架承诺；H3 为升级回归
清单必查项）：

| # | 偏差 | 说明 | 处置 |
|---|------|------|------|
| H1 | session 定型 | session 打开时定型的租户是 Hibernate 写侧校验基准，事务内不可更改；已定型 session 内提权写异租户 flush 抛 `PropertyValueException` | `elevatedInTransaction` 为等效形态（先提权再开事务，§2.3）——Hibernate 固有行为，非缺陷 |
| H2 | filter 粗粒度 | 注解/提权放行是整条关闭 `_tenantId` filter——**读 + 删共用一条防线**，无细粒度控制 | 写侧已由参数判定补齐（R2）；删除随 filter 为语义本然（H3） |
| H3 | filter 对 bulk DML 生效 | 无参形态 `deleteAllInBatch()` / `deleteAll()` 受 filter 保护，异租户行删不掉（实验 D 转正：`TenantDmlBoundaryContractTest#contractD2_noArgDeleteAllInBatchShouldBeFilteredToCurrentTenant`） | 依赖 Hibernate 版本行为，升级回归必查 |

**`elevatedInTransaction` 的作用域退出 handler 空转属预期**：其退出晚于事务提交
（EntityManager 已解绑），`JpaTenantScopeExitHandler` 查 `hasResource` 恒 false 直接返回——
缓存随事务消亡，本无泄露可清。真正触发 `flush()+clear()` 的是事务内嵌套的
`elevated` / `withReadBypass` 作用域（§4.5 ⑤）。

---

## 4. 提权用法（TenantPrivilege）

### 4.1 API 与正确示例

`TenantPrivilege` 是 Spring 单例 bean，**通过构造注入使用**（作用域退出处理器与
租户上下文访问器由容器注入，不同容器各自收集、互不覆盖）：

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final TenantPrivilege tenantPrivilege;   // 构造注入的 bean

    public void crossShopWrite() throws Exception {
        // 无返回值
        tenantPrivilege.elevated(() -> orderRepository.deleteAll());
    }
}
```

```java
// 有返回值
long total = tenantPrivilege.elevated(() -> orderRepository.count());

// 先提权再开事务（推荐：消灭「作用域没罩住事务边界」的时序错误）
OrderResult result = tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
    subOrderRepo.saveAll(List.of(
            subOrderOf("shop-A", ...),      // PO 显式 tenantID=shop-A
            subOrderOf("shop-B", ...)));    // PO 显式 tenantID=shop-B
    inventoryFacade.preoccupy(orderId, items);   // 多店库存预占
    return buildResult();
});
```

C 端跨店下单是典型形态：买家上下文**没有租户**，一次订单拆 N 个店铺子单——每行的归属写在
实体上，整段编排放进提权作用域。证例：
`crossTenantWriteShouldKeepExplicitTenantWhenContextTenantMissing`。

### 4.2 适用场景

只有三类场景允许提权，均需 code review 强审：

1. **平台运维**：superAdmin 查看/代管任意租户数据；
2. **系统编排**：支付回调、定时任务推进各店铺子单状态（actor 本身不属于任何租户）；
3. **C 端跨店编排**：买家一次动作触达多个店铺的数据。

常规业务 CRUD 一律不允许出现 `elevated`。

### 4.3 审计日志

每次进入作用域打一条 INFO 日志：

```text
[TenantPrivilege] elevated scope enter, action=XxxService$$Lambda$123, alreadyActive=false, identity=buyer-001, tenantID=shop-A, at=2026-08-22T10:00:00Z
```

`alreadyActive=true` 表示嵌套提权，review 时应重点追问必要性；`identity` / `tenantID` 为
进入提权时的调用者身份与当前租户（经注入的 `TenantContextAccessor` 解析；不可用时显示
`unknown`）。生产环境建议对该日志关键字配置采集告警，做到「谁在什么时候进了提权」可追溯。

### 4.4 边界行为

- 出作用域自动恢复，异常透传且照样解绑
  （`TenantPrivilegeTest#callableExceptionPassesThroughAndUnbinds`）；
- 嵌套作用域逐层恢复
  （`TenantPrivilegeTest#nestedScopesRestoreLayerByLayer`）；
- 作用域内**无法**篡改绑定值（ScopedValue 语义），不存在「忘了退出」的状态污染。

### 4.5 @CrossTenant：注解读放行（只关读）

读放行标记 `@CrossTenant`（标在方法或类上）声明「方法内（含调用链）的**读操作**关闭租户
过滤，按全租户可见执行」——**只影响读**：

- **写门禁不受注解影响**：注解作用域内写显式异租户实体**照常拒绝**
  （`BusinessException: cross-tenant write is forbidden`）；写仍必须显式 `elevated`（§4.1）。
- **不写 ThreadContext**：`CrossTenantAspect`（`HIGHEST_PRECEDENCE`，先于事务拦截器）以
  `tenantPrivilege.withReadBypass(...)` 建立独立 ScopedValue 读放行状态，退出即恢复、嵌套安全。
- **覆盖两种 session 时序**（§2.2 双保险）：新 session 由 resolver 自查定型 root；已定型
  session 由数据访问点自查关闭 filter——同事务内注解读放行同样生效。

```java
// ✅ 跨租户读报表：方法内全租户可见，退出后恢复隔离
@CrossTenant
public List<Order> listAllOrders() {
    return orderRepository.findAll();        // 全租户可见
}

// ✅ 注解 × 提权组合：读放行 + 显式异租户写（写放行来自 elevated，不是注解）
@CrossTenant
public void exportAndWriteBack() throws Exception {
    tenantPrivilege.elevated(() -> {
        orderRepository.save(crossTenantOrder);   // 显式 tenantID 保留（§3.3）
    });
}

// ❌ 错误：注解不喂写门禁，异租户写仍被拒
@CrossTenant
public void saveForeign() {
    shopRepository.save(crossShopOrder);   // BusinessException: cross-tenant write is forbidden
}
```

适用场景与 §4.2 同理——跨租户读是特权操作，code review 强审，常规业务 CRUD 不允许。
审计日志关键字 `read-bypass scope enter`（格式同 §4.3）。证例：
`crossTenantAnnotatedReadShouldExposeAllTenantsAndRestoreIsolation`（新 session）、
`crossTenantAnnotatedReadShouldBypassInStabilizedSession`（已定型 session）、
`crossTenantNestedAnnotatedScopesAreSafe`（嵌套）、
`crossTenantAnnotatedWriteShouldStillRequireElevation`（AC：写门禁不受注解影响）、
`elevationInsideAnnotatedMethodShouldAllowForeignWrite`（组合）。

#### 覆盖边界与权衡（契约）

**① 拦截点只覆盖 Spring Data repository 层**：数据访问点自查挂在 `TenantRepositoryAspect`
（`this(org.springframework.data.repository.Repository)`）上，仅保护走 Spring Data
Repository 代理的路径。以下直用**不受**数据访问点自查保护：

- `EntityManager` 直用（`persist` / `createQuery` / `find` 等）与 `JdbcTemplate` 直用：绕过
  拦截点，第二保险失效——已定型 session 内 `elevated` / `@CrossTenant` 对其**静默失效**，
  filter 保持进入作用域前的状态，查询仍按原租户过滤；失效方向 fail-closed 安全（失去的是
  「放行」而不是「过滤」）；新 session 场景不受影响（resolver 第一保险仍在）；
- 原生 SQL（`@Query(nativeQuery = true)` / `createNativeQuery` / `JdbcTemplate` 手写 SQL）：
  Hibernate filter 对原生 SQL **不生效**（native 查询直接使用给定 SQL，不参与 filter 机制）
  ——无论是否经过拦截点，tenant-scoped 数据走原生 SQL 既不过滤也不放行，必须自行携带租户
  条件；
- 现状安全：生产代码零此类直用（grep 已证）。契约：未来引入直用路径并期望租户隔离/放行时，
  必须把读下沉到 repository 层（或自行处理），不得依赖本机制。症状排查见附录 A 对应条目。

**② 常态查询的固定开销可接受**：非 bypass 时每次 repository 访问承担固定自检
（`hasResource` + `unwrap(Session)` + `enableFilter` + `setParameter`）。可接受理由：

- 拦截点本就存在——写门禁早已拦截每次 repository 访问，读自查只是同一方法顶部的一次状态
  读取，没有新增代理层级；
- 线程未绑定 EntityManager 时走快速路径（`hasResource` 一次查找即返回，不做 unwrap）；
- filter 名与参数同名同值（`_tenantId` / 当前租户）：`enabledFilters` 按 filter 名判等、查询
  计划缓存键只含 filter 名集不含参数值 → 计划复用不退化。

**③ 注解内新 session 定型 ROOT 是已知权衡**：`@CrossTenant` 作用域内**新建**的 session 经
resolver 自查定型为 ROOT（`isAnyReadBypassActive` → `ROOT_TENANT_ID`）。ROOT 模式下
Hibernate 写侧校验（`@TenantId` assigned-id）保留实体显式 `tenantID`——若绕过写门禁直写
（如 `EntityManager.persist`），显式异租户值会被保留落库；**null-tenant 实体则被落库为
`tenant_id = '__ROOT_TENANT__'`（minor-1 污染：脏数据永久不可见）**。该风险由写门禁兜底：
非提权写强制与当前上下文租户一致（缺失注入、一致放行、不一致拒绝）——
`crossTenantAnnotatedWriteShouldAllowCurrentTenant`（注解内写当前租户照常落库为本租户）与
`crossTenantAnnotatedWriteShouldStillRequireElevation`（注解内写异租户照常拒绝）共同钉住；
异租户写唯一通道仍是显式 `elevated`。此 ROOT 定型是「注解只关读」语义下的既定权衡：注解
不改写侧归属、绝不构成写放行。

**④ 红线：注解内读到的实体仅供读取（F 实证）**：`@CrossTenant` 作用域内 load 出的异租户实体
（`findById` / `findAll` 结果）**不是写入口**——绕过写门禁修改其业务字段并 flush（或依赖
作用域退出 auto-flush）会把跨租户篡改落库（实证：`TenantDmlBoundaryContractTest#contractF_annotatedReadThenMutateBusinessFieldThenFlush`）。
框架只承诺 repository 层写入口的门禁与隔离；`EntityManager` 直用 / `JdbcTemplate` / 手动
`flush()` 直改均不在承诺范围。绕过写入口时归属由存储层兜底（写侧校验或显式
租户值保留），**污染自负**。正确姿势：注解内读到的实体仅用于只读计算；要写，把实体归属显式
声明后走 repository 写入口（非提权写本租户 / 提权写异租户）。

**⑤ 红线：作用域退出 auto-flush 也会落库挂起写**：`elevated` / `withReadBypass` 退出时
`JpaTenantScopeExitHandler` 执行 `flush()+clear()`（顺序即正确性：先落库保挂起写、再失效缓存，
I2）——**不显式 `flush()` 的挂起写（含注解内被修改的实体）同样会落库**。勿依赖「不显式
flush 就不落库」；「注解内读到的实体仅供读取」（红线④）是唯一安全语义。

**⑥ 门禁判定与参数形态绑定**：

- **带实体删除受门禁**：注解/提权作用域内调用带**实体参数**的删除
  （`delete(entity)` / `deleteAll(集合)` / `deleteAllInBatch(集合)`）照常过写门禁——非提权删
  异租户实体被拒（`annotatedDeleteWithForeignTenantPoShouldBeRejected`）。注解的读放行
  **不**放行带实体删除；无实体参数的删除（`deleteById` / `deleteAllById` / `deleteAll()` /
  `deleteAllInBatch()`）随 filter 防线（H3 契约，见 §3.5）；
- **读方法参数勿带租户实体**：门禁对**所有**带 `TenantScopedBasePO` 参数的方法生效，
  读方法也不例外——以租户实体为直接参数（或 Iterable 内元素）的读方法会按两条件判定，
  参数实体与当前视角不一致即被拒。只读查询请用 ID / 标量 / 非租户探针参数（由 filter 兜底，
  无判定开销）。

---

## 5. 异步场景

### 5.1 快照只传身份三元组

`RequestContextPropagatingTaskDecorator` 在提交线程捕获 `ContextSnapshot`
（仅 `tenantID` / `role` / `identity`），worker 线程开头恢复、结尾清理：

```java
executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
```

注意：装饰器需**逐 executor 手动绑定**，无自动配置。没绑定的线程池 = worker 无身份上下文 =
读全空、写全拒（fail-fast）。

### 5.2 提权/读放行状态不随线程传播

ScopedValue 默认不跨线程。父线程正在提权或 `@CrossTenant` 读放行时提交的异步任务，
worker 内 `TenantPrivilege.isActive()` 与 `isReadBypassActive()` 均为 `false`
（`TenantPrivilegeTest#elevationDoesNotLeakIntoNewThread`、
`readBypassDoesNotLeakIntoNewThread`）。
worker 内需要跨租户能力时必须在任务体内**显式声明**：

```java
// ❌ 幻觉：以为父线程的提权还在
CompletableFuture.runAsync(() -> orderRepository.findAll());   // 只看到 worker 自己租户（或空）

// ✅ worker 内显式声明模式与目标租户
CompletableFuture.runAsync(() ->
    tenantPrivilege.elevated(() -> {
        TestTenantNotePO po = new TestTenantNotePO();
        po.setTenantID(targetShopId);       // 目标租户必须显式给出
        ...
    }));
```

目标租户缺失时 worker 会 fail-fast（写入抛 `BusinessException`，读取返回空集），
不会静默落到错误的租户。

### 5.3 手动使用快照必须配对

不经 TaskDecorator 的自管线程（原生 `ExecutorService`、虚拟线程等）手动传播时，
`saveSnapshot` / `clearSnapshot` 必须配对，否则线程复用会串上下文：

```java
// ✅ 标准配对模板
TenantContextAccessor.ContextSnapshot snapshot = accessor.captureSnapshot();
executor.submit(() -> {
    TenantContextAccessor.saveSnapshot(snapshot);
    try {
        doWork();
    } finally {
        TenantContextAccessor.clearSnapshot();
    }
});
```

---

## 6. fail-closed 的空结果语义

上下文租户缺失时，resolver 返回占位值 `__MISSING_TENANT__`，filter 条件变成
`tenant_id = '__MISSING_TENANT__'`——**匹配不到任何行，查询静默返回空集而不是报错**
（Global 表不受影响）。这是设计行为，不是 bug。

```java
threadContext.setTenantID(null);
noteRepository.findAll();      // []     ← 库里有数据也查不到
noteRepository.findById(1L);   // Optional.empty
noteRepository.count();        // 0
```

**排障口诀：库里明明有数据却查不到，先查上下文，再怀疑数据。**

排查顺序：

1. 当前线程 `tenantContextAccessor.getTenantID()` 返回什么？
2. 若是异步 worker：executor 绑定 TaskDecorator 了吗？快照里的 `tenantID` 是 null 吗？
   `saveSnapshot` / `clearSnapshot` 配对了吗？
3. 若是 Web 请求：鉴权层往 `ThreadContext` 写租户了吗？

当前实现对此场景**没有专门的告警日志**（宁可静默也不中断正常请求流），上述三步是唯一的定位
手段；对隔离要求苛刻的项目可在 dev/test profile 自行加断言或日志增强。

证例：`tenantScopedQueryShouldBeFilteredAndFailClosedWhenTenantMissing`、
`globalQueryShouldNotBeFilteredWhenTenantMissing`。

---

## 7. open-in-view 保持关闭

`application.yml` 已显式关闭，**勿重新开启**：

```yaml
spring:
  jpa:
    # OSV 必须关闭：session 打开时一次性定型写入模式，打开时机必须可控
    open-in-view: false
```

原因：OSV 生效时 session 在「请求内第一次触碰数据库」的时刻才打开——这个时机可能早于你的
任何上下文准备逻辑（controller 预载、鉴权后置检查），session 运载的**写入模式**（assign 租户
校验基准）就此随机定型。行为正确与否取决于「路径上谁先碰到数据库」，这种时序耦合靠读代码守
不住。此外 OSV 把 JDBC 连接占用从事务级膨胀到请求级，高并发下连接池先行耗尽。

关闭后时序确定：准备上下文（含提权作用域）→ 事务开启 → session 打开 → resolver 定型。

---

## 8. 按主键加载的版本要求

`em.find()` / `findById` 应用 `_tenantId` 过滤是 HHH-16830 修复的能力，
**Hibernate ≤ 6.4 按主键加载不过滤**——意味着那个版本区间下 `findById` 能读到别的租户的行，
构成越权漏洞。

本仓库锁定 `hibernate-core 7.4.1.Final`（已包含修复），**禁止降级 Hibernate 版本**；
升级时须回归验证 `findById` 隔离（证例：`tenantScopedFindByIdShouldBeFiltered`）。

---

## 9. ORM 无关性

多租户机制的稳定契约在核心层（common），均不依赖任何 ORM 框架（grep 门）：

- `TenantWriteGate`：写门禁纯函数判定（两条件：提权状态 × 实体归属）+ 哨兵常量
  （MISSING/ROOT）权威定义，零 Spring/JPA 依赖；
- `TenantScopeExitHandler`：作用域退出通知 SPI（I2：缓存与视角一致），实现由容器收集注入
  （每个容器收集自己的实现列表）；
- `TenantContextAccessor`：身份上下文唯一读取源（两级优先级：request scope → 线程快照）；
- `TenantPrivilege`：提权/读放行状态唯一判断源（`isActive` / `isReadBypassActive` /
  `isAnyReadBypassActive`），纯 ScopedValue 状态，零持久化概念；Spring 单例 bean，
  经构造注入使用；
- `@CrossTenant`：读放行契约声明（只关读，写门禁不受影响）。

存储差异收敛进适配层：读隔离（`TenantReadIsolationAdapter`：读过滤默认生效 / 读放行显式绕过，
每次数据访问前自查状态启停 filter；JPA 实现 `JpaTenantReadIsolationAdapter`）与 I2 缓存一致性
（`JpaTenantScopeExitHandler`：作用域退出 flush+clear）。写门禁判定 100% 在 common——Aspect
只剩参数遍历与注入执行。未来更换 MyBatis 等框架时：

1. 用 interceptor（MyBatis `Interceptor`）在每条 SQL 前自查同一状态：任一读放行激活 →
   不追加 tenant 条件；否则 → 所有 tenant-scoped 表自动追加 `WHERE tenant_id = #{当前租户}`，
   租户取自 `TenantContextAccessor`；
2. insert 自动填充 `tenant_id` 列；缺失时的行为保持 fail-closed（追加恒假条件，返回空集）；
3. 写入门禁沿用 §3 的两条件判定——interceptor 遍历参数中的租户实体，调 common
   `TenantWriteGate.decideInjection`，按返回值执行注入，判断源用 `TenantPrivilege.isActive()`；
4. 作用域退出通知复用 `TenantScopeExitHandler`（MyBatis 侧实现清 SqlSession 缓存，作为
   bean 被容器收集注入）；
5. 核心层与业务代码零改动——只要业务侧只依赖上述契约。

与框架耦合的技术点（resolver 单次调用、filter 启停时序、`@TenantId` 校验、H1-H3 偏差）全部
收敛在 JPA 适配层内部，换框架时替换适配层实现即可。

---

## 附录 A：常见症状速查表

| 症状 | 根因 | 修复 |
|------|------|------|
| 查询静默返回空集，库里有数据 | 上下文租户缺失（请求没设 / worker 无快照 / 快照 EMPTY），
  fail-closed 生效 | 按 §6 排查顺序检查 `getTenantID()`；绑 decorator、配对快照 |
| `PropertyValueException: assigned tenant id differs...` | session 已按具体租户定型，
  却以显式异租户实体走了 ORM 写入（绕过门禁或提权作用域未罩住事务边界） | 别绕过 repository；
  写必须用 `elevatedInTransaction` 或把 `elevated` 移到事务外层（§2.3 / §3.4） |
| 提权了但写入仍被拒 `cross-tenant write is forbidden` | 该路径不在提权作用域内
  （作用域提前退出了），或实体租户为哨兵值（`__MISSING_TENANT__` / `__ROOT_TENANT__`） | 检查
  作用域覆盖范围；实体显式设置合法租户，勿填哨兵值（§3.3） |
| 提权下写入被拒 `elevated write requires explicit tenantId but tenant is missing or blank` | ④：
  提权 + 实体空归属（null/空白）→ 拒绝，不再注入 | 实体显式声明归属（§3.3）；框架不会替你
  发明归属——「谁做的」是 identity 职责 |
| 写入被拒 `invalid tenantId cannot be used as entity tenant` | 实体归属填了哨兵值
  （MISSING/ROOT） | 哨兵是框架内部值，不可作实体归属（§3.3） |
| 异步任务里查不到数据 / 写入抛 `tenantID is required...` | worker 无身份快照
  （decorator 未绑定）或提权未随线程传播而任务依赖它 | §5：绑定 decorator；任务体内显式
  `elevated` + 显式目标租户 |
| 线程池偶发串租户 | 手动 `saveSnapshot` / `clearSnapshot` 未配对，ThreadLocal 残留 | 按 §5.3
  配对模板改造，清理放进 `finally` |
| 升级/换依赖后 `findById` 能读到其他租户 | Hibernate 降级到 ≤ 6.4，HHH-16830 修复丢失 |
  锁定 ≥ 7.4.x 并回归 `tenantScopedFindByIdShouldBeFiltered`（§8） |
| `@CrossTenant` 标注了却不生效（读仍是单租户过滤） | ① Spring AOP 代理语义：同 bean 内自调用
  不经过切面；目标类不是 Spring bean（`new` 出来）/ `final` 类 / `private` 方法；
  ② 注解已生效，但数据访问直用 EM/JdbcTemplate/原生 SQL，绕过了 repository 层拦截点
  （§4.5「覆盖边界与权衡」） | ① 跨租户读下沉到独立 bean 的 public 方法并注入调用；确认类
  可被代理；② 读路径下沉到 repository 层执行，勿直用 EM/原生 SQL（§4.5） |
