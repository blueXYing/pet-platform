package com.petplatform.community.biz.application.port;

import java.util.List;
import java.util.Map;

/** Cross-domain nickname/avatar projection (boot adapts the user query API). */
public interface CommunityUserDirectoryPort {

    record UserCard(String userId, String nickname, String avatarUrl) {}

    /** Missing ids are omitted; the kernel falls back to a safe placeholder. */
    Map<String, UserCard> lookup(List<String> userIds);
}
