# RabbitMQ 中断恢复验证

## 已验证范围

本次在独立 `campuspass_recovery` schema 中真实停止并重启 RabbitMQ，应用与 MySQL/Redis 保持运行；未使用 Mock。

1. RabbitMQ 停止期间，报名 HTTP 接口仍返回 `202`。
2. MySQL 中该申请保持 `enrollment_requests.status=PENDING`，对应 Outbox 为 `PROCESS_ENROLLMENT / PENDING`，发布失败重试计数为 3。
3. 恢复 RabbitMQ 后，Outbox 重投成功并标记 `SENT`，申请最终进入 `PENDING_CONFIRM`。
4. 事件 `capacity=1`、`occupied_count=1`、有效报名行数 1、容量流水余额 1；没有重复报名或超额。

另在独立 `campuspass_recovery` schema 与 Redis DB15 中验证数据库事务失败补偿：对专用测试活动临时安装仅影响 `enrollments` 插入的 MySQL 故障触发器，消费者处理后申请进入 `FAILED / PROCESSING_FAILED`；`occupied_count=0`、有效报名行数为 0、容量流水余额为 0。Redis 预占租约到期后预占计数归零。移除触发器后，以另一模拟学生重新报名，同一活动进入 `PENDING_CONFIRM`，容量和流水均为 1。该验证使用真实 MySQL、Redis、RabbitMQ，不影响 `campuspass` 压测 schema。

还验证 Redis 服务重启期间的持久请求恢复：RabbitMQ 停止时提交报名，接口返回 `202`，MySQL 请求与 Outbox 为 `PENDING`、Redis 预占计数为 1；重启 Redis 后 AOF 正常加载，预占租约在恢复 Broker 前自然到期。恢复 RabbitMQ 后，Outbox 重试并进入 `SENT`，报名到达 `PENDING_CONFIRM`；容量 1、占用数 1、有效报名 1、容量流水余额 1，报名队列 ready/unacked 为 0/0、死信队列为 0。该过程说明数据库请求与 Outbox 可在 Redis 短暂重启后继续驱动最终处理；不代表 Redis 中的短期预占本身跨重启永久保留。

消费者进程中断也完成了真实验证：在独立测试活动上用短时 MySQL 延迟触发器让消费者事务保持运行，确认 RabbitMQ `unacked=1` 后强制终止本次启动的 Java 消费者进程。事务回滚，队列消息恢复为 `ready=1 / unacked=0`，请求仍为 `PENDING`。移除触发器并重启应用后，消息再次消费成功；请求为 `PENDING_CONFIRM`，容量、占用报名和流水余额均为 1，报名队列积压及死信均为 0。触发器已移除。

## 复现步骤

1. 启动本地依赖并等待健康检查：

   ```powershell
   ./scripts/start.ps1
   ```

2. 使用独立 schema（推荐 `campuspass_recovery`）启动应用，设置 `ENROLLMENT_ASYNC=true`。通过 [`docs/api.md`](api.md) 创建临时组织者、单名额活动并发布，再注册一个临时学生。
3. 停止本项目 Broker：

   ```powershell
   docker compose stop rabbitmq
   ```

4. 学生提交报名并保存返回的 `requestId`。预期仍返回 `202`，并可在 MySQL 中查到 `PENDING` 请求和 `PENDING` Outbox：

   ```sql
   SELECT id,status,result_code FROM enrollment_requests WHERE id='<requestId>';
   SELECT id,event_type,status,attempts FROM outbox_messages WHERE aggregate_id='<requestId>';
   ```

5. 恢复 Broker：

   ```powershell
   docker compose up -d --wait rabbitmq
   ```

6. 轮询 `GET /api/enrollment-requests/{requestId}`，等待 `PENDING_CONFIRM` 或 `WAITLISTED`；再运行 `scripts/check-invariants.sql` 确认容量与流水一致。
7. 测试结束后停止应用，再运行 `./scripts/stop.ps1`。该命令保留命名数据卷；恢复测试 schema 和数据可供后续检查。

## 未覆盖项

已验证 Broker 重启后的 Outbox 重投、Redis 服务重启期间持久请求的恢复、消费者进程强制终止后的 RabbitMQ 重投，以及数据库事务失败后的预占租约释放和恢复重试。该矩阵不等于生产级混沌测试；多实例、网络分区、Redis 数据丢失等更复杂场景仍需另行验证。
