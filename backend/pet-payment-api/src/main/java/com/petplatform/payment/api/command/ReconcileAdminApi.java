package com.petplatform.payment.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.payment.api.dto.ReconcileTypes.ListReconcileDiffsQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileDiffView;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcilePage;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileRunSummary;
import com.petplatform.payment.api.dto.ReconcileTypes.ResolveReconcileDiffCommand;
import java.time.LocalDate;

/**
 * Contract 80 §4.6 daily SACS reconciliation and manual discrepancy acknowledgement API.
 * Daily reconciliation only records alerts and never mutates settlement ledger states automatically.
 */
public interface ReconcileAdminApi {
    ReconcilePage<ReconcileDiffView> listDiffs(ListReconcileDiffsQuery query);
    ReconcileDiffView resolveDiff(ResolveReconcileDiffCommand command);
    ReconcileRunSummary runDailyReconcile(LocalDate date, CommandContext context);
}
