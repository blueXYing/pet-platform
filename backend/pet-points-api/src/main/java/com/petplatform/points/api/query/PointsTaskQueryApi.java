package com.petplatform.points.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInStatusView;
import com.petplatform.points.api.dto.PointsTaskTypes.TasksView;

/**
 * Sign-in + referral read side (contract 58). Reads are always scoped to the session user
 * (C face) or gated by the admin RBAC action (config face); no cross-user coordinate exists.
 */
public interface PointsTaskQueryApi {

    /** Current-day sign-in state: signedToday, streak, configured amount, last-7-days flags. */
    SignInStatusView signInStatus(SignInStatusQuery query);

    /** Task-center + milestone projection shared by the tasks and rewards pages. */
    TasksView tasks(TasksQuery query);

    /** Ops configuration view (defaults when no row exists). */
    ConfigView config(ConfigQuery query);

    record SignInStatusQuery(QueryContext context) {}

    record TasksQuery(QueryContext context) {}

    record ConfigQuery(QueryContext context) {}
}
