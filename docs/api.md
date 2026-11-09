# API 与业务状态

所有需要登录的请求使用 `Authorization: Bearer <accessToken>`。时间采用本机/数据库时区 `Asia/Shanghai`，JSON 时间格式 `yyyy-MM-ddTHH:mm:ss`。

## 双 Token

注册：

```http
POST /api/auth/register
Content-Type: application/json

{"username":"student001","password":"AtLeast8Chars","displayName":"校园同学"}
```

登录返回 `accessToken`、`refreshToken`、两种 Token 的过期秒数及公开用户资料。Access Token 为 15 分钟；Refresh Token 为 7 天，刷新请求只需提交当前 Refresh Token：

```json
{"refreshToken":"<refresh-token>"}
```

服务端将 Refresh Token 的会话 UUID 放在不透明串前缀中，随机秘密部分只存散列。成功刷新后旧串立即失效。并发调用时只有一个请求能轮换成功，其余收到 `401 REFRESH_TOKEN_REUSED`。Access Token 过期后仍可使用有效 Refresh Token 刷新。

## 活动与报名

活动时间使用 ISO local datetime，例如 `2026-11-02T18:30:00`。免费活动不收款。

```http
POST /api/events/1/enrollments
Authorization: Bearer <access-token>
Idempotency-Key: signup-01J9M3T4ABCDEF
```

返回 `202 Accepted`，例如：

```json
{"requestId":"55f01ad1-2e69-4114-bfe0-a689a323f3d3","status":"ACCEPTED"}
```

`202` 只表示请求已持久化并进入处理，不代表获得名额。使用 `GET /api/enrollment-requests/{requestId}` 查到 `PENDING_CONFIRM`、`WAITLISTED` 或拒绝/失败结果后，再按状态继续。

| 报名状态 | 是否占用名额 | 可执行动作 |
|---|---:|---|
| `WAITLIST` | 否 | 查队列位置、取消 |
| `PENDING_CONFIRM` | 是 | 5 分钟内确认、取消 |
| `CONFIRMED` | 是 | 活动开始前取消、获取核销码 |
| `CHECKED_IN` | 是 | 终态 |
| `CANCELLED`、`EXPIRED` | 否 | 可重新报名，新 generation 加入队尾 |

确认与过期同时发生时由数据库行锁和状态条件更新决定唯一胜者。已核销记录不能取消或再次核销。

## 错误响应

```json
{"timestamp":"2026-10-20T20:00:00Z","code":"ALREADY_ENROLLED","message":"已有有效报名或候补记录"}
```

常见状态码：400 参数/业务输入错误；401 Token 无效或会话撤销；403 角色/资源归属不满足；404 资源不存在；409 状态冲突、满员或重复有效报名；429 登录限流；500 未预期服务错误。
