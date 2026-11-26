package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.InstructionRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coordinates one active Lakala 1296 {@code query_plus} attempt for a persisted split instruction.
 * Always calls the channel outside any DB transaction and delegates verified results to
 * {@link SettlementReceiptWriter}.
 */
public final class SettlementQueryCoordinator {
    private final PaymentSettlementStore store;
    private final SettlementChannel channel;
    private final SettlementReceiptWriter writer;
    private final ZoneId channelTimeZone;
    private final Clock clock;

    public SettlementQueryCoordinator(DataSource source, SettlementChannel channel,
            SettlementReceiptWriter writer, ZoneId channelTimeZone, Clock clock) {
        this.store = new PaymentSettlementStore(Objects.requireNonNull(source, "source"));
        this.channel = Objects.requireNonNull(channel, "channel");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.channelTimeZone = Objects.requireNonNull(channelTimeZone, "channelTimeZone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SettlementQueryTask.Outcome execute(long instructionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("settlement query coordinator requires no outer transaction");
        }
        InstructionRow row = store.settlement.selectInstructionById(instructionId);
        if (row == null) {
            return SettlementQueryTask.Outcome.NOT_FOUND;
        }
        CmdType expectedCmd;
        if ("SPLIT".equals(row.instructionType)) {
            expectedCmd = CmdType.SEPARATE;
        } else if ("FALLBACK".equals(row.instructionType)) {
            expectedCmd = CmdType.FALLBACK;
        } else {
            return SettlementQueryTask.Outcome.NOT_FOUND;
        }
        if ("SUCCEEDED".equals(row.status) || "FAILED".equals(row.status)) {
            return SettlementQueryTask.Outcome.DONE;
        }
        QueryPlusInput input = new QueryPlusInput(
                LocalDateTime.ofInstant(clock.instant(), channelTimeZone).withNano(0),
                row.merchantNo,
                row.separateNo,
                row.outSeparateNo);
        ExpectedSplit expected = new ExpectedSplit(
                row.outSeparateNo, row.separateNo, expectedCmd, row.logNo, row.logDate);
        VerifiedSplitOutcome verified;
        try {
            verified = channel.querySeparateOutcome(input, expected);
        } catch (ChannelUnknownException unknown) {
            return SettlementQueryTask.Outcome.PENDING;
        }
        SettlementReceiptWriter.ReceiptResult result = writer.receiveQuery(
                row.settlementOrderId, row.id, verified);
        return result.terminal() ? SettlementQueryTask.Outcome.DONE : SettlementQueryTask.Outcome.PENDING;
    }
}
