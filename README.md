# Ecommerce Backend

B2B2C 平台电商后端（一期 MVP）：以交易链路核心域（商品/库存/订单/支付/物流）为深度目标。
模块化单体，Java 25（虚拟线程）+ Spring Boot 4.1，基于 projectScaffolding 脚手架
（DDD 分层、多租户隔离、属性级变更追踪）。前端仓库：`ecommerce-frontend`。

## 动机

电商盲区补充的实践项目：把交易闭环（商品发布与审核、库存防超卖、下单拆单、mock 支付、
物流履约、超时流转）做成可经得起深挖的领域模型，而非流程式 CRUD。

## 亮点

- 三端一体：`/mall`（买家）、`/seller`（商家）、`/admin`（平台）前缀与安全链一一对应
- 完整交易闭环：入驻开店 → 商品上架审核 → 下单 → 支付 → 发货 → 签收 → 超时自动流转
- 异构读写分离：MySQL 主写 + Debezium 同步 + PostgreSQL 读库（搜索/统计走镜像）

## 快速开始

```bash
mvn clean test                # 全量测试
mvn spring-boot:run -pl server  # 启动（默认 H2 内存库）
curl http://localhost:19891/actuator/health   # 健康检查
```

结构（模块、包地图、依赖方向）见 `structure.md`。

## 许可证

MIT