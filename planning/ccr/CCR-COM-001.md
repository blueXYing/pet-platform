# CCR-COM-001 宠友圈社区核心域

- 状态：用户已确认（2026-10-28）
- 关联：C 端 PRD 5.1.5/5.1.6（社区动态、动态管理、关注/粉丝/收藏）、SSOT §16 消息中心、62 号契约（百科读侧）、71/72 号契约（营销中心）
- 背景：2026-10-14 的切片裁决曾将 COM-001 社区后端整体排除在 V1 之外；2026-10-28 用户明确裁决「社区后端一起做」，并确认首页「宠友圈热门」必须接真实社区数据，不再使用「即将上线」占位。该裁决覆盖此前「V1 无社区域」的切片说明，不覆盖 SSOT 中 V1 不做私信 IM、AI 客服、直播的边界。

## 范围

1. C 端社区读侧：公开动态流（发现/热门/关注/我的）、动态详情、评论分页、话题字典、用户公开主页计数。
2. C 端社区写侧：发布动态（文字 + 最多 9 张图片）、删除自己的动态、点赞/取消点赞、收藏/取消收藏、关注/取消关注、发表评论、删除自己的评论、举报动态/评论。
3. 图片：`POST /api/v1/c/community/media` multipart 上传（PNG/JPEG，≤2MiB，≤2000×2000），公开只读素材 `GET /api/v1/c/community/media/{mediaId}`；配置 `OSS_*` 时落私有 OSS，未配置时数据库 BLOB 回退。
4. 审核：发布时做敏感词/长度/图片归属校验；命中敏感词进入 `PENDING_REVIEW`，其余自动 `PUBLISHED`；运营可审核通过、下线、删除动态，处理举报并记录审计。V1 不接外部内容审核服务。
5. 首页「宠友圈热门」由社区热门流驱动；「热门服务」「植物&异宠」由运营内容位驱动（CCR-MKT-003）。
6. 权限：C 端写操作强制 MINIAPP Bearer；公开读允许匿名 GET；运营审核使用 `community.post.read` / `community.post.moderate` / `community.report.read` / `community.report.handle`；话题写侧 V1 未开放，话题由初始化/运营种子维护。
7. 所有写操作带 `X-Request-Id` 幂等；动态/评论/举报/审核写动作 append-only 审计。

## 不做

- 不做私信 IM、直播、AI 客服、黑名单完整能力（SSOT 维持）。
- 不做视频上传/转码；V1 社区图片仅 PNG/JPEG。
- 不做评论点赞、二级回复、@ 提及、话题创建（用户侧）；话题由运营维护。
- 不接入社区积分任务（`COMMUNITY_POST` 继续 `NOT_OPEN`）。
- 不做自动推荐算法训练；热门排序使用点赞/评论/收藏的确定性权重。
- 不做外部审核服务、内容安全人工复核双人审批；V1 单运营，审计与理由必填。

## 错误码

- `COMMUNITY_POST_NOT_FOUND` 404：不存在、已删除、未公开或无权查看，防枚举统一 404。
- `COMMUNITY_COMMENT_NOT_FOUND` 404：同上。
- `COMMUNITY_TOPIC_NOT_FOUND` 404：话题停用/不存在。
- `COMMUNITY_MEDIA_INVALID` 400：非 PNG/JPEG、尺寸/字节超限、空文件。
- `COMMUNITY_MEDIA_TOO_LARGE` 413：超过 2MiB。
- `COMMUNITY_POST_INVALID` 400：正文/标题/图片数量/可见性不合法。
- `COMMUNITY_COMMENT_INVALID` 400：评论正文不合法。
- `COMMUNITY_REPORT_INVALID` 400：举报对象/理由不合法。
- `COMMUNITY_STATE_NOT_ALLOWED` 409：已删除动态等状态冲突。
- `COMMUNITY_ALREADY_REPORTED` 409：同一用户对同一对象重复举报。

## 验收

- 匿名可读热门/发现流；登录后可发布、点赞、收藏、关注、评论、举报；自己的动态/评论可删除。
- 发布带图片时先上传媒体，再以 mediaId 绑定；图片公开可读且不可枚举他人私有上下文。
- 首页「宠友圈热门」展示真实已发布动态；空数据时整块隐藏，不显示假数据。
- 运营可下线/删除违规动态并填写理由；举报可处理；动作有审计与 requestId 幂等。
- 动态被下线/删除后，列表、详情、首页热门、历史分享链接均不再展示正文。
