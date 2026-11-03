# 73-宠友圈社区契约 v0.1（CCR-COM-001）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以实现 PR 的 CI 为准。
日期：2026-10-28。来源：C 端 PRD 社区动态/动态管理/关注粉丝收藏、SSOT §16 消息中心；
用户 2026-10-28 裁决「社区后端一起做」，覆盖既有切片中「COM-001 一行不做」的说明。
关联：62 号百科契约（同模块读侧）、71/72 号营销契约、`docs/03-database/64-Community-Storage-v0.1.sql`。

## 1. 范围与开关

- C 端：动态流（最新/热门/关注/我的/收藏）、动态详情、评论、话题、用户公开主页；发布/删除动态、
  点赞/取消、收藏/取消、关注/取消、评论/删除自己的评论、举报。
- 图片：`POST /api/v1/c/community/media` multipart；`GET /api/v1/c/community/media/{mediaId}` 公开只读。
- 运营：动态审核（通过/下线/删除）、举报处理；话题字典由初始化数据提供，运营话题写侧暂不开放。
- 开关：`pet.community.enabled`（模块装配）、`pet.community.c.http.enabled`（C 面）、
  `pet.community.admin.http.enabled`（运营面）；C 面还要求 `pet.auth.c.enabled`。默认 false。
- 匿名 GET 可浏览公开内容；所有写操作要求 MINIAPP Bearer；运营写要求 ADMIN_WEB + 动作码。

## 2. C 端路由

| 方法/路径 | 鉴权 | 参数/体 | 返回 |
|---|---|---|---|
| GET `/api/v1/c/community/posts` | 可选 | `scope=LATEST\|HOT\|FOLLOWING\|MINE\|FAVORITES`（默认 LATEST）、`topicCode?`、`page/pageSize` | 分页动态卡 |
| GET `/api/v1/c/community/posts/{postId}` | 可选 | — | 动态详情卡 |
| GET `/api/v1/c/community/posts/{postId}/comments` | 可选 | `page/pageSize` | 分页评论 |
| GET `/api/v1/c/community/hot` | 可选 | `limit` 1..6（默认 3） | 热门动态卡 |
| GET `/api/v1/c/community/topics` | 匿名 | — | 启用话题 |
| GET `/api/v1/c/community/users/{userId}` | 可选 | — | 昵称/头像/动态数/粉丝数/关注数/是否关注 |
| GET `/api/v1/c/community/media/{mediaId}` | 匿名 | — | 图片字节，ETag，365 天 immutable |
| POST `/api/v1/c/community/media` | Bearer | multipart `file` PNG/JPEG ≤2MiB ≤2000×2000 | `mediaId/url/...` |
| POST `/api/v1/c/community/posts` | Bearer | `title?`(≤60)、`content`(1..2000)、`imageIds?`(≤9)、`topicCode?` | `{id,status,created,occurredAt}` |
| DELETE `/api/v1/c/community/posts/{postId}` | Bearer | — | receipt（仅作者） |
| POST/DELETE `/api/v1/c/community/posts/{postId}/likes` | Bearer | — | `{postId,active,count,changed}` |
| POST/DELETE `/api/v1/c/community/posts/{postId}/favorites` | Bearer | — | 同上 |
| POST/DELETE `/api/v1/c/community/users/{userId}/follow` | Bearer | — | receipt |
| POST `/api/v1/c/community/posts/{postId}/comments` | Bearer | `content`(1..500) | receipt |
| DELETE `/api/v1/c/community/comments/{commentId}` | Bearer | — | receipt（仅作者） |
| POST `/api/v1/c/community/reports` | Bearer | `targetType=POST\|COMMENT`、`targetId`、`reasonCode`、`reasonText?` | receipt |

分页上限 50；`FOLLOWING/MINE/FAVORITES` 无登录 401。发布时命中敏感词进入
`PENDING_REVIEW`，公开列表/热门不可见；其余自动 `PUBLISHED`。`COMMUNITY_POST` 积分任务继续
`NOT_OPEN`（本契约不改积分规则）。

## 3. 运营路由

| 方法/路径 | 动作码 | 参数 | 返回 |
|---|---|---|---|
| GET `/api/v1/admin/community/posts` | community.post.read | status?/keyword?/page/pageSize | 全状态动态 |
| POST `/api/v1/admin/community/posts/{postId}/decision` | community.post.moderate | decision=APPROVE\|OFFLINE\|DELETE、reason?（下线/删除必填 1..500）、expectedVersion | receipt |
| GET `/api/v1/admin/community/reports` | community.report.read | status?/page/pageSize | 举报列表 |
| POST `/api/v1/admin/community/reports/{reportId}/resolve` | community.report.handle | decision=RESOLVE\|DISMISS、note(1..200) | receipt |

运营不代发/编辑动态正文；审核动作 append-only 审计；requestId 幂等。

## 4. 数据与可见性

- 表：`community_topic`、`community_media`、`community_post`、`community_post_like`、
  `community_post_favorite`、`community_follow`、`community_comment`、`community_report`、
  `community_audit`、`community_command_binding`。
- 动态 `status`：`PENDING_REVIEW|PUBLISHED|OFFLINE|DELETED`；`visibility=PUBLIC`（V1）。
- 公开列表/详情/热门只展示 `PUBLISHED + PUBLIC`；作者可查看自己的非删除内容；已删除/未公开对
  他人统一 404 防枚举。动态/评论删除为软删除，历史分享链接不再返回正文。
- 热门排序：`like_count*3 + comment_count*2 + favorite_count*4` 降序，再按发布时间/ID 降序。
- 图片配置 `OSS_*` 时存私有对象桶并由应用代理读取；未配置时数据库 BLOB 回退。对象键不对外暴露。

## 5. 测试与验收

- 真 MySQL 内核测试覆盖：发布/流/点赞/评论/关注/举报、敏感词审核、图片上传/读取、
  运营通过后公开。
- 匿名可读；写缺 Bearer 401；非作者删除 404；重复举报 409；错误码按 12 号注册表映射。
