# CampusPass

CampusPass 是一个面向高校讲座、社团活动等场景的校园活动报名系统。它提供账号权限、活动发布、限额报名、严格 FIFO 候补、5 分钟确认、取消递补、签到核销和站内通知。

## 技术栈

Java 21、Spring Boot 2.7.18、Spring Security、MyBatis-Plus、MySQL 8、Redis 7、RabbitMQ 4、Spring AMQP、Caffeine、Flyway、Sentinel、Springdoc OpenAPI、JMeter。

## 本地启动

1. 复制 `.env.example` 为 `.env`，修改本机密码与 JWT 密钥。示例凭据只用于本机演示。
2. 启动三项依赖：

   ```powershell
   ./scripts/start.ps1
   ```

3. 启动 Spring Boot：

   ```powershell
   $env:BOOTSTRAP_ADMIN_USERNAME = 'admin'
   $env:BOOTSTRAP_ADMIN_PASSWORD = 'ChangeMe-Admin-2026'
   $env:JWT_SECRET = '替换成至少32字节的本机随机密钥'
   mvn -s .mvn/settings.xml spring-boot:run
   ```

应用监听 `127.0.0.1:8082` 对应的 HTTP 端口；Compose 中 MySQL、Redis、RabbitMQ 和管理 UI 端口都仅绑定本机。停止容器使用 `./scripts/stop.ps1`，数据卷会保留；如需清空，须自行明确删除 `campuspass` 项目卷。

应用 JDBC 连接池默认上限为 50，可通过 `DB_POOL_SIZE` 调整；需要与 MySQL `max_connections` 及部署资源一并配置。

集成测试、压测工具及相关脚本仅保留在本地，不随本仓库发布；文档中的相关复现步骤需另外准备这些工具。本仓库不包含 SQL 文件（包括 Flyway 迁移和 scripts 下的 SQL 脚本）。首次启动前需自行准备匹配当前代码的数据库表结构及迁移方案；仅克隆本仓库无法通过 Flyway 自动建表。文档和性能测试脚本中引用的 SQL 文件需另外准备。启动时可通过环境变量创建一个 ADMIN；管理员创建 ORGANIZER 后，组织者即可建立并发布活动。接口文档：`http://localhost:8082/swagger-ui.html`。

依赖下载使用项目内 `.mvn/settings.xml` 指向 Maven Central。若当前网络只允许内部 Nexus，请按本机仓库要求修改该文件。

## 接口概览

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/register` | 公开 | 学生注册 |
| POST | `/api/auth/login` | 公开 | 账号密码登录，返回双 Token |
| POST | `/api/auth/refresh` | 公开 | 原子轮换 Refresh Token |
| POST | `/api/auth/logout` | 登录 | 撤销当前设备会话 |
| GET | `/api/auth/me` | 登录 | 当前账号 |
| POST | `/api/admin/users` | ADMIN | 创建 ORGANIZER 或 ADMIN |
| PATCH | `/api/admin/users/{id}/status` | ADMIN | 禁用账号并撤销其会话 |
| GET/POST | `/api/events` | 公开／ORGANIZER | 活动列表／创建 |
| GET/PUT/DELETE | `/api/events/{id}` | 公开／所属组织者或 ADMIN | 详情／草稿修改／删除 |
| POST | `/api/events/{id}/publish` | 所属组织者或 ADMIN | 发布活动 |
| POST | `/api/events/{id}/enrollments` | STUDENT | 需要 `Idempotency-Key`，返回 202 和 requestId |
| GET | `/api/enrollment-requests/{requestId}` | 请求所属 STUDENT | 查询最终受理状态 |
| GET | `/api/enrollments/mine` | STUDENT | 我的报名 |
| GET | `/api/enrollments/{id}/queue-position` | 报名本人 | 候补位置 |
| POST | `/api/enrollments/{id}/confirm` | 报名本人 | 5 分钟内确认 |
| POST | `/api/enrollments/{id}/cancel` | 报名本人 | 活动开始前取消 |
| POST | `/api/enrollments/{id}/checkin-code` | 报名本人 | 获取有效核销码 |
| POST | `/api/enrollments/{id}/checkin` | 所属组织者或 ADMIN | 核销一次性报名码 |
| GET/PATCH | `/api/notifications` | STUDENT | 站内通知／标记已读 |

请求示例、状态机和错误码见 [`docs/api.md`](docs/api.md)。

## 一致性设计

- Access Token 是 15 分钟 JWT；Refresh Token 是 7 天随机不透明串。Redis 只保存 Refresh Token 的 SHA-256、会话角色和账号信息。Lua 脚本原子比较并轮换 Refresh Token，因此同一旧 Token 只能成功一次。每次 Access Token 请求都会检查 Redis 会话，退出或禁用账号后立即失效。
- 报名入口把请求和 Outbox 写入同一 MySQL 事务，返回 202；Redis Lua 处理幂等标记、临时名额预占和候补顺序。Outbox Dispatcher 只在 RabbitMQ Publisher Confirm 且未 Return 后标记已发送。
- 消费者在 MySQL 活动行锁和事务内创建报名；容量条件更新是最终保护，保证占用数不超过总名额。消费事务完成后才 ACK。候补次序以数据库递增 sequence 为最终依据，Redis ZSet 用作排队索引。
- 每个报名轮次使用独立 generation。确认、取消、超时和核销均为状态条件更新；确认超时由 RabbitMQ TTL 队列触发，并由每 30 秒数据库扫描兜底。通知写入站内通知表。
- 活动详情使用 Redis＋Caffeine；写操作提交后删除 Redis 值并广播本地缓存失效，展示缓存不参与名额和权限判定。

## 测试与压测

```powershell
./scripts/test.ps1
```

`test.ps1` 编译并运行单元测试；`./scripts/test-integration.ps1` 使用真实 MySQL、Redis、RabbitMQ 运行端到端测试（刷新竞争、幂等申请、权限、候补递补、核销和注销撤销）。集成测试创建独立的 `campuspass_integration` schema，并清理 CampusPass 专属消息队列，不会清空主压测库。

真实依赖启动后可运行 [`scripts/seed-benchmark.sql`](scripts/seed-benchmark.sql)，生成 10,000 个合成学生和 100 场合成活动（第 1 场 1,000 个名额）。账号前缀 `benchstudent`，数据仅用于本机压测；不要导入真实学生资料。测试期间将 `LOGIN_IP_LIMIT` 调高并在两组测试中保持一致。

优化前后的固定开关：

- 基线：`ENROLLMENT_ASYNC=false LOCAL_CACHE_ENABLED=false`（同步处理报名、仅 Redis 缓存）。
- 优化：`ENROLLMENT_ASYNC=true LOCAL_CACHE_ENABLED=true`（Outbox＋RabbitMQ 异步处理、Redis＋Caffeine 两级缓存）。

压测计划、命令、数据口径和实测结果单独记录在 [`docs/performance.md`](docs/performance.md)。没有记录的数字均不作为性能结论。

RabbitMQ 中断后的 Outbox 恢复步骤与实测证据见 [`docs/recovery.md`](docs/recovery.md)。

每次压测前先停止应用和 JMeter，运行 `./scripts/prepare-benchmark.ps1`（会清空 CampusPass 专属队列并重建合成数据），记录输出的热点活动 ID。基线启动应用前设置 `$env:ENROLLMENT_ASYNC='false'`、`$env:LOCAL_CACHE_ENABLED='false'`；优化组设为 `true`。正式对照两组均设置 `$env:LOGIN_IP_LIMIT='10000'`、`$env:SENTINEL_EVENT_DETAIL_QPS='5000'`、`$env:DB_POOL_SIZE='20'`，保持配置一致后重启应用。每个并发档重复三次：

```powershell
./scripts/run-jmeter-stage.ps1 -Mode baseline -Threads 20 -Repeat 1 -EventId <热点活动ID>
```

正式对照档为 20、50、100 并发，`Repeat` 改为 1、2、3；完成基线后按同样顺序跑 `-Mode optimized`。200 并发在本机出现 JDBC 连接池超时，因此 200/500 不计入正式性能比较。每组保留独立数据重置、应用启动参数、JTL 和 HTML 报告。

## 当前已知边界

该项目是单机演示和基准测试工程；默认开发凭据必须替换，不能直接公网部署。Redis Lua 预占用于减少并发入口竞争，MySQL 条件更新和事务才是容量最终事实。RabbitMQ 重启重投、Redis 重启期间持久请求恢复、消费者进程强制终止后的消息重投、MySQL 写入失败补偿均已在本机实测；这些验证不替代多实例生产环境的混沌测试。
