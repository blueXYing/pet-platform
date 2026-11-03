package com.petplatform.community.biz.application;

import com.petplatform.community.api.CommunityAdminApi.AdminAuthorization;

/** Boot adapts the admin authorization owner; every call re-proves the action code. */
public interface CommunityAdminAuthorizationPort {

    record Check(String sessionId, long sessionGeneration, String operatorId, String actionCode,
            String resourceType, String resourceId, String cityCode) {}

    record Decision(boolean allowed, String authzVersion, java.time.OffsetDateTime checkedAt) {}

    Decision check(Check check);
}
