# 结构文档

> 代码地图：模块划分、包结构与依赖方向（概述级）。类级职责与契约见各文件 Javadoc。
> 本文件描述当前现状，随代码演进更新。

## 模块划分

| 模块 | 内容 | 项目内依赖 |
|------|------|-----------|
| `common` | 脚手架公共能力原样保留：统一响应、业务异常、断言、事件总线、ID 生成、`@ScaffoldGenerated`、租户写门禁规则 | 无 |
| `api` | 对外契约：三端 REST DTO（record）+ 分页/错误码公共契约 + AuthApi | `common` |
| `server` | Spring Boot 应用：12 域 DDD 领域层 + 基础设施（持久化 / 多租户 / 变更追踪） | `api`、`common`、外部 `change-tracking-api` |

依赖方向自底向上：`common` → `api` → `server`。

## 包地图

### common

| 包 | 职责 |
|----|------|
| `com.nona.api` | 统一响应体 `HttpResponse` |
| `com.nona.annotation` | `@ScaffoldGenerated` 模板生成标识 |
| `com.nona.events` | 事件总线：`Event` / `Dispatcher` / `EventHandler` / `AbstractHandler` |
| `com.nona.exceptions` | `BusinessException`（业务异常） |
| `com.nona.persistence` | `BaseRepository`（仓储接口）、`Sequence`（Snowflake ID 实现） |
| `com.nona.tenant` | 租户写门禁规则（存储无关，零 Spring/JPA 依赖） |
| `com.nona.util` | `BusinessAssert`（断言）、`IDUtils`、`JacksonUtil` |

### api

| 包 | 职责 |
|----|------|
| `com.nona.api.auth` | 认证契约：`AuthApi`（登录/注册，三端共用）与 DTO、`Portal` |
| `com.nona.api.common` | 公共契约：`ErrorCode`（分域分段错误码）、`PageQuery`/`PageResult`（统一分页） |
| `com.nona.api.mall` / `seller` / `admin` | 三端契约（REST DTO + API 接口），按端分包 |

### server

| 包 | 职责 |
|----|------|
| `com.nona` | 应用入口 `EcommerceApplication`（JPA 仓库扫描配置） |
| `com.nona.application.{mall,seller,admin}` | 三端用例编排层：跨域用例与事务边界；跨租户读写放行只允许出现于此层 |
| `com.nona.application.support` | 跨端共用编排（如支付回调推进链路） |
| `com.nona.application.advice` | 全局异常处理 `ExceptionAdviser` |
| `com.nona.domain.{catalog,inventory,order}` | 核心域：商品/库存/订单（一期实现） |
| `com.nona.domain.{identity,payment,search}` | 通用域：用户 IAM/支付（网关抽象+mock）/搜索（纯读 PG） |
| `com.nona.domain.{logistics}` | 支撑域：物流运单（一期实现，模拟推进） |
| `com.nona.domain.{marketing,review,settlement,notification,analytics}` | 支撑域（II 期占位） |
| `com.nona.inf.context` | 跨切面请求上下文：`ThreadContext`、`TenantContextAccessor`、`TenantPrivilege`、`@CrossTenant`、异步传播装饰器 |
| `com.nona.inf.persistence` | 持久化基础设施：PO 基类 / 转换器 / DifferRepository / 多租户 / 变更追踪 |
| `com.nona.inf.{replica,security,storage,timeout}` | 基础设施占位：PG 读库 / JWT 安全链 / 文件存储防腐层 / 超时调度引擎 |
| `com.nona.web.{mall,seller,admin}` | REST controller（路由前缀 /mall /seller /admin） |

## 领域分层约束

```
web/controller → application(用例编排) → domain(实体/工厂/仓储接口) ← inf.persistence(PO/转换/仓储实现)
                                          domain → ports(ACL) → 其他 domain（仅允许 ACL 方向）
```

- 禁止 controller 直调 repository；domain 之间直接引用实体（必须走 ACL 接口）
- 事务边界 = application 层用例方法；domain 方法不做 @Transactional
- 跨租户读放行（@CrossTenant）与写放行（TenantPrivilege.elevatedInTransaction）只允许出现在 application 层用例方法上

## 阅读起点

1. `EcommerceApplication`（`server`）——应用入口，理解 JPA 扫描与自动配置范围
2. `com.nona.api.auth.AuthApi` / `com.nona.api.common`——对外契约形态（后续接口按此风格扩展）
3. `DifferRepository`（`inf.persistence.repository`）——变更追踪与持久化的核心集成点
4. `TenantContextAccessor`（`inf.context`）——多租户与请求上下文的统一入口
