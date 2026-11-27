package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.OnboardingTypes.AdoptContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.DiscardContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.ListOnboardingsQuery;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingView;
import com.petplatform.payment.api.dto.OnboardingTypes.RevealOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.RetryOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.SubmitOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SyncOnboardingCommand;
import com.petplatform.payment.api.dto.SettlementTypes.UpsertSettlementConfigCommand;
import com.petplatform.payment.biz.application.PaymentMerchantBindings.Binding;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingNotifications;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.BindApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.BindProgress;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.ContractApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.ContractProgress;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.EcApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.LedgerApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.Uploaded;
import com.petplatform.payment.biz.settlement.MerchantOnboardingEligibilityPort.MerchantOnboardingFacts;
import com.petplatform.payment.biz.settlement.MerchantOnboardingTasks.Outcome;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider.AssetContent;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Real MySQL 8 integration tests for the merchant acquiring onboarding state machine
 * (CCR-PAY-ONBOARD-001 §4/§6, contract 81): signed callbacks drive the chain 电子合同 → 进件 →
 * 开通分账 → 绑定, unknown submissions are never resent, rejections stop the chain until an operator
 * acts, polls time out into the matching rejected state, and only the completed chain writes the
 * acquiring pair that payments are gated on.
 */
class MerchantOnboardingMySqlTest {
    private static final String MERCHANT = "9001";
    private static final String PLATFORM_MCH = "822651053990MW8";
    private static final String PLATFORM_TERM = "Q6426723";
    private static final String PLATFORM_APP = "wxc12540a8f32b372c";
    private static final String PLATFORM_RECEIVER = "SR20261126PLAT01";
    private static final String NOTIFY_BASE = "https://pet.example/api/v1/callbacks/lakala/onboard";
    private static final String LEGAL_ID = "342523199905298212";
    private static final String EC_APPLY = "863797013367664640";
    private static final String EC_NO = "QT20221021000157861";
    private static final String CONTRACT_ID = "202104062502898611";
    private static final String MER_INNER = "4002021040692311689";
    private static final String MER_CUP = "82229007011009E";
    private static final String TERM = "B0100551";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final List<String> CONTRACT_CALLS = List.of(
            "upload:BANK_CARD", "upload:BUSINESS_LICENCE", "upload:FR_ID_CARD_BEHIND",
            "upload:FR_ID_CARD_FRONT", "upload:MERCHANT_PHOTO", "upload:SHOPINNER", "addMer");
    private static KeyPair notifyKeys;
    private static KeyPair foreignKeys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        notifyKeys = generator.generateKeyPair();
        foreignKeys = generator.generateKeyPair();
    }

    @Test
    void signedCallbacksDriveTheWholeChainAndWriteTheAcquiringPair() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.service.defaults(MERCHANT, query()).eligible());
            assertTrue(f.service.defaults(MERCHANT, query()).missingConfig().isEmpty());

            OnboardingView draft = f.service.saveProfile(save(0, id("save-1")));
            assertEquals("DRAFT", draft.status());
            assertEquals(1, draft.version());
            assertEquals("342****8212", draft.merchant().larIdcardMasked());
            assertEquals("6212****0000", draft.settlement().acctNoMasked());
            assertEquals("*三", draft.merchant().larName());
            assertEquals(List.of("SAVE_PROFILE", "SUBMIT"), draft.allowedActions());
            // Same requestId + same payload replays the stored result; same requestId + other payload conflicts.
            assertEquals(draft.version(), f.service.saveProfile(save(0, id("save-1"))).version());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> f.service.saveProfile(profile(0, id("save-1"), "另一个原因"))).code());
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_merchant_onboarding_audit WHERE action_type='PROFILE_SAVE'"));
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class, () -> f.service.submit(
                    new SubmitOnboardingCommand(command("submit-stale"), MERCHANT, 9))).code());

            // An onboarding record alone already closes the payment gate and the manual pair entry.
            gateClosed(f);
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class, () -> f.admin().upsertConfig(
                    new UpsertSettlementConfigCommand(command("cfg-manual"), MERCHANT, 7, new BigDecimal("0.6000"),
                            true, "822OTHER", "T2", "手改"))).code());

            OnboardingView signing = f.service.submit(new SubmitOnboardingCommand(command("submit-1"), MERCHANT, 1));
            assertEquals("EC_SIGNING", signing.status());
            assertEquals("https://sign.example/x", signing.ecSignUrl());
            assertEquals(List.of("SAVE_PROFILE", "SYNC"), signing.allowedActions());
            assertEquals("WAITING_SIGN", signing.steps().getFirst().status());
            assertEquals("NONE", signing.steps().get(1).status());
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='MERCHANT_ONBOARDING_POLL'"));
            // Platform parameters are frozen on the row; the MCC came from the merchant-type mapping.
            assertEquals("0742", signing.platform().mccCode());
            assertEquals("90.50", signing.platform().splitLowestRatio());
            assertEquals(1, signing.platform().feeRates().size());
            EcApplication ec = f.channel.ec;
            assertEquals(NOTIFY_BASE + "/ec", ec.retUrl());
            assertEquals(LEGAL_ID, ec.legalIdCard());
            assertEquals("示例科技有限公司", ec.partnerName());
            assertEquals("宠物平台商家合作协议", ec.cooperationAgreementName());
            assertEquals("90.50", ec.merchantLowestRatio());

            // The signing callback only counts with a valid signature of the trusted key.
            byte[] signedEc = ecSigned().getBytes(StandardCharsets.UTF_8);
            String unknownApply = ecSigned().replace(EC_APPLY, "999999999999999999");
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> f.service.receiveEcNotification(Map.of(), signedEc)).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> f.service.receiveEcNotification(sign(foreignKeys, signedEc), signedEc)).code());
            assertEquals("EC_SIGNING", f.view().status());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> f.service.receiveEcNotification(
                            sign(notifyKeys, unknownApply.getBytes(StandardCharsets.UTF_8)),
                            unknownApply.getBytes(StandardCharsets.UTF_8))).code());
            // A pending notification is audited and changes nothing.
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned()
                    .replace("\"COMPLETED\"", "\"UNDONE\"").replace(EC_NO, "").getBytes(StandardCharsets.UTF_8)), ecSigned()
                    .replace("\"COMPLETED\"", "\"UNDONE\"").replace(EC_NO, "").getBytes(StandardCharsets.UTF_8));
            assertNull(f.view().ecNo());
            f.service.receiveEcNotification(sign(notifyKeys, signedEc), signedEc);
            OnboardingView signed = f.view();
            assertEquals(EC_NO, signed.ecNo());
            assertNull(signed.ecSignUrl());
            assertEquals("DONE", signed.steps().getFirst().status());
            f.service.receiveEcNotification(sign(notifyKeys, signedEc), signedEc);
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_merchant_onboarding_audit WHERE action_type='EC_SIGNED'"));

            // Advance: six attachments uploaded, then 进件 with the decrypted profile and the e-contract number.
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            OnboardingView applying = f.view();
            assertEquals("CONTRACT_APPLYING", applying.status());
            assertEquals(CONTRACT_ID, applying.contractId());
            assertEquals("APPLYING", applying.steps().get(1).status());
            ContractApplication contract = f.channel.contract;
            assertEquals(6, contract.fileData().size());
            assertEquals(EC_NO, contract.contractNo());
            assertEquals(NOTIFY_BASE, contract.retUrl());
            assertEquals(LEGAL_ID, contract.larIdcard());
            assertEquals("6212260200102400000", contract.acctNo());
            assertEquals("91110108MA2G7LK368", contract.merBlis());
            assertEquals("0742", contract.mccCode());
            assertEquals("CLOUD_DISTRIBUTION", contract.posType());
            assertEquals("T+1", contract.settlePeriod());
            assertEquals("302", contract.feeData().getFirst().feeRateTypeCode());
            assertEquals(CONTRACT_CALLS, f.channel.calls.subList(1, f.channel.calls.size()));
            assertEquals(2, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='MERCHANT_ONBOARDING_POLL'"));

            f.service.receiveContractNotification(sign(notifyKeys, contractApproved().getBytes(StandardCharsets.UTF_8)), contractApproved().getBytes(StandardCharsets.UTF_8));
            OnboardingView ledger = f.view();
            assertEquals("LEDGER_APPLYING", ledger.status());
            assertEquals(MER_CUP, ledger.merCupNo());
            assertEquals(TERM, ledger.termNo());
            assertEquals("DONE", ledger.steps().get(1).status());

            f.service.advanceStep(9001, 0);
            LedgerApplication ledgerApplication = f.channel.ledger;
            assertEquals(MER_CUP, ledgerApplication.merCupNo());
            assertEquals(EC_NO, ledgerApplication.eleContractNo());
            assertEquals(new BigDecimal("90.50"), ledgerApplication.splitLowestRatio());
            assertEquals("13900000000", ledgerApplication.contactMobile());
            assertEquals(NOTIFY_BASE + "/ledger-open", ledgerApplication.retUrl());

            f.service.receiveLedgerOpenNotification(sign(notifyKeys, ledgerApproved().getBytes(StandardCharsets.UTF_8)), ledgerApproved().getBytes(StandardCharsets.UTF_8));
            assertEquals("BIND_APPLYING", f.view().status());
            assertEquals("DONE", f.view().steps().get(2).status());

            f.channel.calls.clear();
            f.service.advanceStep(9001, 0);
            BindApplication bind = f.channel.bind;
            assertEquals(MER_CUP, bind.merCupNo());
            assertEquals(PLATFORM_RECEIVER, bind.receiverNo());
            assertEquals("SPLIT_COOPERATION_FILE", bind.entrustFile().attType());
            assertEquals(NOTIFY_BASE + "/bind", bind.retUrl());
            assertEquals(List.of("upload:SPLIT_COOPERATION_FILE", "applyBind"), f.channel.calls);

            f.service.receiveBindNotification(sign(notifyKeys, bindApproved().getBytes(StandardCharsets.UTF_8)), bindApproved().getBytes(StandardCharsets.UTF_8));
            OnboardingView active = f.view();
            assertEquals("ACTIVE", active.status());
            assertEquals(List.of(), active.allowedActions());
            assertNotNull(active.completedAt());
            assertEquals("DONE", active.steps().get(3).status());
            assertEquals(MER_CUP + "|" + TERM, f.queryString(
                    "SELECT CONCAT(lakala_merchant_no,'|',lakala_term_no) FROM settlement_config WHERE merchant_id=9001"));
            assertEquals("7|0.6000|1", f.queryString(
                    "SELECT CONCAT(settle_days,'|',fee_rate,'|',enabled) FROM settlement_config WHERE merchant_id=9001"));
            assertEquals("SYSTEM", f.queryString(
                    "SELECT operator_type FROM settlement_config_audit WHERE merchant_id=9001"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_merchant_onboarding_audit WHERE action_type='ACTIVATED'"));
            // Once active the profile is closed, the pair is system-owned, and payments route to the merchant.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> f.service.saveProfile(save(active.version(), id("save-late")))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class, () -> f.admin().upsertConfig(
                    new UpsertSettlementConfigCommand(command("cfg-manual-2"), MERCHANT, 8, new BigDecimal("0.7000"),
                            true, "822OTHER", "T2", "手改"))).code());
            Binding binding = f.bindings().require(MERCHANT, "710302");
            assertEquals(MER_CUP, binding.merchantNo());
            assertEquals(TERM, binding.termNo());
            assertEquals(PLATFORM_APP, binding.subAppId());
            assertEquals(8, f.admin().upsertConfig(new UpsertSettlementConfigCommand(command("cfg-days"), MERCHANT, 8,
                    new BigDecimal("0.7000"), true, null, null, "调整账期")).settleDays());
            assertEquals(MER_CUP + "|" + TERM, f.queryString(
                    "SELECT CONCAT(lakala_merchant_no,'|',lakala_term_no) FROM settlement_config WHERE merchant_id=9001"));
            // Audited reveal returns plaintext once.
            assertEquals(LEGAL_ID, f.service.reveal(new RevealOnboardingCommand(
                    command("reveal-1"), MERCHANT, "商家来电核对")).larIdcard());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> f.service.reveal(new RevealOnboardingCommand(
                            command("reveal-1"), MERCHANT, "商家来电核对"))).code());
            assertEquals(1, f.service.list(new ListOnboardingsQuery(query(), null, null, 1, 20)).items().size());
            assertTrue(f.service.listAudits(MERCHANT, query()).stream().anyMatch(a -> "REVEAL".equals(a.actionType())));
            assertFalse(f.service.listAudits(MERCHANT, query()).toString().contains(LEGAL_ID));
        }
    }

    @Test
    void unknownOutcomeIsNeverResubmittedAndCanBeAdoptedOrDiscarded() throws Exception {
        try (Fixture f = new Fixture()) {
            f.service.saveProfile(save(0, id("save-1")));
            f.service.submit(new SubmitOnboardingCommand(command("submit-1"), MERCHANT, 1));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.channel.addMerOutcome = "UNKNOWN";
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            OnboardingView unknown = f.view();
            assertEquals("CONTRACT_UNKNOWN", unknown.status());
            assertEquals("ADD_MER_OUTCOME_UNKNOWN", unknown.lastErrorCode());
            assertEquals(List.of("ADOPT_CONTRACT", "DISCARD_CONTRACT"), unknown.allowedActions());
            assertEquals("UNKNOWN", unknown.steps().get(1).status());
            // No path may resend 进件 while the outcome is unknown.
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 1));
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> f.service.sync(new SyncOnboardingCommand(command("sync-unknown"), MERCHANT))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> f.service.saveProfile(profile(unknown.version(), id("edit"), "线下提供"))).code());
            assertEquals(1, f.channel.count("addMer"));

            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class, () -> f.service.adoptContract(
                    new AdoptContractCommand(command("adopt-stale"), MERCHANT, 99, CONTRACT_ID, "已核实"))).code());
            OnboardingView adopted = f.service.adoptContract(
                    new AdoptContractCommand(command("adopt-1"), MERCHANT, unknown.version(), CONTRACT_ID, "已核实"));
            assertEquals("CONTRACT_APPLYING", adopted.status());
            assertNull(adopted.lastErrorCode());
            assertEquals(Outcome.PENDING, f.service.pollStep(9001, 0));
            assertEquals("CONTRACT_APPLYING", f.view().status());
            f.channel.contractQuery = new ContractProgress(CONTRACT_ID, "INNER_CHECK_REJECTED", "营业执照照片模糊", null, null, null);
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 1));
            OnboardingView rejected = f.view();
            assertEquals("CONTRACT_REJECTED", rejected.status());
            assertEquals("营业执照照片模糊", rejected.rejectReason());
            assertEquals(List.of("SAVE_PROFILE"), rejected.allowedActions());

            // An edit restarts from DRAFT; blank secrets and names keep the stored values.
            OnboardingView draft = f.service.saveProfile(blankSecrets(rejected.version(), id("save-keep"), "线下提供"));
            assertEquals("DRAFT", draft.status());
            assertNull(draft.contractId());
            assertEquals("342****8212", draft.merchant().larIdcardMasked());
            assertEquals("*三", draft.merchant().larName());
            assertEquals("6212****0000", draft.settlement().acctNoMasked());
            f.service.submit(new SubmitOnboardingCommand(command("submit-2"), MERCHANT, draft.version()));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.channel.addMerOutcome = "UNKNOWN";
            f.service.advanceStep(9001, 0);
            assertEquals("CONTRACT_UNKNOWN", f.view().status());
            OnboardingView discarded = f.service.discardContract(new DiscardContractCommand(
                    command("discard-1"), MERCHANT, f.view().version(), "拉卡拉确认无此进件"));
            assertEquals("DRAFT", discarded.status());
            assertNull(discarded.lastErrorCode());
            assertNull(discarded.ecNo());
            assertNull(discarded.contractId());
            assertEquals(2, f.channel.count("addMer"));
        }
    }

    @Test
    void rejectionsStopTheChainUntilTheOperatorRetriesOrEdits() throws Exception {
        try (Fixture f = new Fixture()) {
            // 进件 refused synchronously: back to DRAFT carrying the channel reason.
            f.service.saveProfile(save(0, id("save-1")));
            f.service.submit(new SubmitOnboardingCommand(command("submit-1"), MERCHANT, 1));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.channel.addMerOutcome = "REJECTED";
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            OnboardingView refused = f.view();
            assertEquals("DRAFT", refused.status());
            assertEquals("ADD_MER_REJECTED", refused.lastErrorCode());
            assertTrue(refused.lastErrorMessage().contains("注册名称含有敏感词"), refused.lastErrorMessage());
            assertEquals(List.of("SAVE_PROFILE", "SUBMIT"), refused.allowedActions());
            assertEquals("NONE", refused.steps().get(1).status());
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_merchant_onboarding_audit WHERE action_type='CONTRACT_REJECTED'"));

            // A refused electronic-contract application keeps DRAFT and surfaces the channel message.
            f.channel.addMerOutcome = "OK";
            f.channel.ecOutcome = "REJECTED";
            ApiException ecRefused = assertThrows(ApiException.class, () -> f.service.submit(
                    new SubmitOnboardingCommand(command("submit-ec-rejected"), MERCHANT, refused.version())));
            assertEquals(CommonApiCodes.CONFLICT, ecRefused.code());
            assertTrue(ecRefused.getMessage().contains("拉卡拉拒绝电子合同申请"), ecRefused.getMessage());
            assertEquals("EC_REJECTED", f.view().lastErrorCode());
            assertEquals("DRAFT", f.view().status());
            f.channel.ecOutcome = "UNKNOWN";
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class, () -> f.service.submit(
                    new SubmitOnboardingCommand(command("submit-ec-unknown"), MERCHANT, f.view().version()))).code());
            assertEquals("EC_OUTCOME_UNKNOWN", f.view().lastErrorCode());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> f.service.sync(new SyncOnboardingCommand(command("sync-draft"), MERCHANT))).code());
            f.channel.ecOutcome = "OK";

            // 进件 refused by the Lakala review: the operator edits and a brand-new 进件 is created.
            long version = f.view().version();
            f.service.submit(new SubmitOnboardingCommand(command("submit-3"), MERCHANT, version));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.service.advanceStep(9001, 0);
            f.service.receiveContractNotification(sign(notifyKeys, contractApproved()
                    .replace("WAIT_FOR_CONTACT", "INNER_CHECK_REJECTED")
                    .replace("\"contractMemo\":\"\"", "\"contractMemo\":\"门头照不清\"")
                    .getBytes(StandardCharsets.UTF_8)), contractApproved()
                    .replace("WAIT_FOR_CONTACT", "INNER_CHECK_REJECTED")
                    .replace("\"contractMemo\":\"\"", "\"contractMemo\":\"门头照不清\"")
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals("CONTRACT_REJECTED", f.view().status());
            assertEquals("门头照不清", f.view().rejectReason());
            version = f.service.saveProfile(profile(f.view().version(), id("save-2"), "线下提供")).version();
            f.service.submit(new SubmitOnboardingCommand(command("submit-4"), MERCHANT, version));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.service.advanceStep(9001, 0);
            f.service.receiveContractNotification(sign(notifyKeys, contractApproved().getBytes(StandardCharsets.UTF_8)), contractApproved().getBytes(StandardCharsets.UTF_8));
            assertEquals("LEDGER_APPLYING", f.view().status());

            // 开通分账 refused synchronously, then by review; every retry files a fresh application.
            f.channel.ledgerOutcome = "REJECTED";
            f.service.advanceStep(9001, 0);
            assertEquals("LEDGER_REJECTED", f.view().status());
            assertEquals(List.of("RETRY"), f.view().allowedActions());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class, () -> f.service.retry(
                    new RetryOnboardingCommand(command("retry-stale"), MERCHANT, 99, null, null))).code());
            version = f.service.retry(new RetryOnboardingCommand(command("retry-1"), MERCHANT, f.view().version(), null, "重新提交")).version();
            assertEquals("LEDGER_APPLYING", f.view().status());
            assertNull(f.view().steps().get(2).submittedAt());
            f.channel.ledgerOutcome = "OK";
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            assertNotNull(f.view().steps().get(2).submittedAt());
            f.service.receiveLedgerOpenNotification(sign(notifyKeys, ledgerApproved()
                    .replace("\"auditStatus\":\"1\"", "\"auditStatus\":\"2\"")
                    .replace("\"remark\":\"审核通过\"", "\"remark\":\"委托书与立项不一致\"")
                    .getBytes(StandardCharsets.UTF_8)), ledgerApproved()
                    .replace("\"auditStatus\":\"1\"", "\"auditStatus\":\"2\"")
                    .replace("\"remark\":\"审核通过\"", "\"remark\":\"委托书与立项不一致\"")
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals("LEDGER_REJECTED", f.view().status());
            assertEquals("委托书与立项不一致", f.view().rejectReason());
            f.service.retry(new RetryOnboardingCommand(command("retry-2"), MERCHANT, f.view().version(), null, null));
            f.service.advanceStep(9001, 0);
            f.service.receiveLedgerOpenNotification(sign(notifyKeys, ledgerApproved().getBytes(StandardCharsets.UTF_8)), ledgerApproved().getBytes(StandardCharsets.UTF_8));
            assertEquals("BIND_APPLYING", f.view().status());
            assertEquals(3, f.channel.count("applyLedger"));

            // 绑定 refused by review; a retry may replace the cooperation agreement.
            f.service.advanceStep(9001, 0);
            f.service.receiveBindNotification(sign(notifyKeys, bindApproved()
                    .replace("\"auditStatus\":\"1\"", "\"auditStatus\":\"2\"")
                    .getBytes(StandardCharsets.UTF_8)), bindApproved()
                    .replace("\"auditStatus\":\"1\"", "\"auditStatus\":\"2\"")
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals("BIND_REJECTED", f.view().status());
            assertEquals("REJECTED", f.view().steps().get(3).status());
            f.service.retry(new RetryOnboardingCommand(command("retry-bind"), MERCHANT, f.view().version(), "999", "换协议"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM payment_merchant_onboarding WHERE cooperation_asset_id='999'"));
            assertEquals("BIND_APPLYING", f.view().status());
            f.service.advanceStep(9001, 0);
            f.service.receiveBindNotification(sign(notifyKeys, bindApproved().getBytes(StandardCharsets.UTF_8)), bindApproved().getBytes(StandardCharsets.UTF_8));
            assertEquals("ACTIVE", f.view().status());
            assertEquals(2, f.channel.count("applyBind"));
        }
    }

    @Test
    void pollsFallBackWhenCallbacksNeverArriveAndTimeOutIntoRejectedStates() throws Exception {
        try (Fixture f = new Fixture()) {
            // Signing never completes: the last poll returns the record to DRAFT.
            f.service.saveProfile(save(0, id("save-1")));
            f.service.submit(new SubmitOnboardingCommand(command("submit-1"), MERCHANT, 1));
            assertEquals(Outcome.PENDING, f.service.pollStep(9001, 0));
            assertEquals("EC_SIGNING", f.view().status());
            assertEquals(Outcome.DONE, f.service.pollStep(9001, MerchantOnboardingTasks.POLL_MAX_RETRY - 1));
            OnboardingView draft = f.view();
            assertEquals("DRAFT", draft.status());
            assertEquals("EC_SIGN_TIMEOUT", draft.lastErrorCode());
            assertNull(draft.ecNo());
            assertEquals(List.of("SAVE_PROFILE", "SUBMIT"), draft.allowedActions());

            // 进件 stays under review until the polls are exhausted.
            long version = draft.version();
            f.service.submit(new SubmitOnboardingCommand(command("submit-2"), MERCHANT, version));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.service.advanceStep(9001, 0);
            assertEquals(Outcome.PENDING, f.service.pollStep(9001, 0));
            assertEquals("CONTRACT_APPLYING", f.view().status());
            assertEquals(Outcome.DONE, f.service.pollStep(9001, MerchantOnboardingTasks.POLL_MAX_RETRY - 1));
            assertEquals("CONTRACT_REJECTED", f.view().status());
            assertTrue(f.view().rejectReason().contains("超时"), f.view().rejectReason());

            // Answers found by the poll move the chain exactly like the callback path.
            version = f.service.saveProfile(profile(f.view().version(), id("save-3"), "线下提供")).version();
            f.service.submit(new SubmitOnboardingCommand(command("submit-3"), MERCHANT, version));
            f.channel.ecCompleted = true;
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 0));
            assertEquals(EC_NO, f.view().ecNo());
            f.service.advanceStep(9001, 0);
            f.channel.contractQuery = new ContractProgress(CONTRACT_ID, "WAIT_FOR_CONTACT", "", MER_INNER, MER_CUP, TERM);
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 0));
            assertEquals("LEDGER_APPLYING", f.view().status());
            f.service.advanceStep(9001, 0);
            assertEquals(Outcome.PENDING, f.service.pollStep(9001, 0));
            assertEquals(Outcome.DONE, f.service.pollStep(9001, MerchantOnboardingTasks.POLL_MAX_RETRY - 1));
            assertEquals("LEDGER_REJECTED", f.view().status());
            assertTrue(f.view().rejectReason().contains("超时"), f.view().rejectReason());
            f.service.retry(new RetryOnboardingCommand(command("retry-1"), MERCHANT, f.view().version(), null, null));
            f.service.advanceStep(9001, 0);
            f.channel.ledgerValid = true;
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 0));
            assertEquals("BIND_APPLYING", f.view().status());
            f.service.advanceStep(9001, 0);
            assertEquals(Outcome.PENDING, f.service.pollStep(9001, 0));
            assertEquals(Outcome.DONE, f.service.pollStep(9001, MerchantOnboardingTasks.POLL_MAX_RETRY - 1));
            assertEquals("BIND_REJECTED", f.view().status());
            f.service.retry(new RetryOnboardingCommand(command("retry-bind"), MERCHANT, f.view().version(), null, null));
            f.service.advanceStep(9001, 0);
            f.channel.bindQuery = new BindProgress(true, true, "通过");
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 0));
            assertEquals("ACTIVE", f.view().status());
        }
    }

    @Test
    void unknownLedgerAndBindOutcomesAreConfirmedByMerchantNumberLookups() throws Exception {
        try (Fixture f = new Fixture()) {
            f.service.saveProfile(save(0, id("save-1")));
            f.service.submit(new SubmitOnboardingCommand(command("submit-1"), MERCHANT, 1));
            f.service.receiveEcNotification(sign(notifyKeys, ecSigned().getBytes(StandardCharsets.UTF_8)), ecSigned().getBytes(StandardCharsets.UTF_8));
            f.service.advanceStep(9001, 0);
            f.service.receiveContractNotification(sign(notifyKeys, contractApproved().getBytes(StandardCharsets.UTF_8)), contractApproved().getBytes(StandardCharsets.UTF_8));
            f.channel.ledgerOutcome = "UNKNOWN";
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            OnboardingView ledger = f.view();
            assertEquals("LEDGER_APPLYING", ledger.status());
            assertEquals("LEDGER_OUTCOME_UNKNOWN", ledger.lastErrorCode());
            assertNotNull(ledger.steps().get(2).submittedAt());
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            assertEquals(1, f.channel.count("applyLedger"));
            f.channel.ledgerValid = true;
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 0));
            assertEquals("BIND_APPLYING", f.view().status());

            f.channel.bindOutcome = "UNKNOWN";
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            assertEquals("BIND_APPLYING", f.view().status());
            assertEquals("BIND_OUTCOME_UNKNOWN", f.view().lastErrorCode());
            assertEquals(Outcome.DONE, f.service.advanceStep(9001, 0));
            assertEquals(1, f.channel.count("applyBind"));
            f.channel.bindQuery = new BindProgress(true, false, "合作协议不完整");
            assertEquals(Outcome.DONE, f.service.pollStep(9001, 0));
            assertEquals("BIND_REJECTED", f.view().status());
            assertEquals("合作协议不完整", f.view().rejectReason());

            // A bind callback addressed to another platform receiver is not ours.
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> f.service.receiveBindNotification(sign(notifyKeys, bindApproved()
                            .replace(PLATFORM_RECEIVER, "SR20261126OTHER").getBytes(StandardCharsets.UTF_8)), bindApproved()
                            .replace(PLATFORM_RECEIVER, "SR20261126OTHER").getBytes(StandardCharsets.UTF_8))).code());
        }
    }

    @Test
    void editingKeepsBlankSecretsIncludingADifferentSettler() throws Exception {
        try (Fixture f = new Fixture()) {
            f.service.saveProfile(settler(0, id("save-settler"), "110101199003074258", "2040-01-01"));
            OnboardingView first = f.view();
            assertEquals("58", first.settlement().acctTypeCode());
            assertEquals("01", first.settlement().acctIdType());
            assertEquals("110****4258", first.settlement().acctIdcardMasked());
            assertEquals(9, first.attachments().size());
            // Every sensitive field blank, settler mode still on: the stored settler ID and expiry survive.
            OnboardingView kept = f.service.saveProfile(keepSettler(first.version(), id("save-keep-settler"), "线下提供"));
            assertEquals("110****4258", kept.settlement().acctIdcardMasked());
            assertEquals("110101199003074258", f.service.reveal(new RevealOnboardingCommand(
                    command("reveal-settler"), MERCHANT, "核对结算人")).acctIdcard());
            // Switching back to "settler is the legal person" needs the three settler attachments removed.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> f.service.saveProfile(legalPersonSettler(kept.version(), id("save-legal"), true))).code());
            OnboardingView legal = f.service.saveProfile(legalPersonSettler(kept.version(), id("save-legal"), false));
            assertNull(legal.settlement().acctIdType());
            assertNull(legal.settlement().acctIdcardMasked());
            assertEquals(6, legal.attachments().size());
            assertEquals("DRAFT", legal.status());
        }
    }

    // ---- Fixtures and helpers -------------------------------------------------------------

    /** Records every call and answers from a script, so the test states exactly what Lakala did. */
    private static final class ScriptedChannel implements MerchantOnboardingChannel {
        final List<String> calls = new ArrayList<>();
        EcApplication ec;
        ContractApplication contract;
        LedgerApplication ledger;
        BindApplication bind;
        String ecOutcome = "OK";
        String addMerOutcome = "OK";
        String ledgerOutcome = "OK";
        String bindOutcome = "OK";
        boolean ledgerValid;
        boolean ecCompleted;
        BindProgress bindQuery = new BindProgress(false, false, null);
        ContractProgress contractQuery = new ContractProgress(CONTRACT_ID, "REVIEW_ING", "", null, null, null);

        int count(String call) {
            return (int) calls.stream().filter(call::equals).count();
        }

        @Override
        public Uploaded uploadAttachment(String orderNo, String orgCode, String attType, String extName, byte[] content) {
            calls.add("upload:" + attType);
            return new Uploaded(attType, "G1/" + attType + ".png");
        }

        @Override
        public EcApplied applyElectronicContract(EcApplication application) {
            calls.add("ec");
            ec = application;
            if ("REJECTED".equals(ecOutcome)) throw new Rejected("087900", "三四要素认证失败");
            if ("UNKNOWN".equals(ecOutcome)) throw new Unknown("IO", null);
            return new EcApplied(EC_APPLY, "https://sign.example/x");
        }

        @Override
        public EcProgress queryElectronicContract(String orderNo, String orgId, String ecApplyId) {
            return new EcProgress(ecCompleted, ecCompleted ? EC_NO : null);
        }

        @Override
        public ContractAdded addMerchant(ContractApplication application) {
            calls.add("addMer");
            contract = application;
            if ("REJECTED".equals(addMerOutcome)) throw new Rejected("OP10100", "三代进件进件校验失败(注册名称含有敏感词)");
            if ("UNKNOWN".equals(addMerOutcome)) throw new Unknown("IO", null);
            return new ContractAdded(CONTRACT_ID);
        }

        @Override
        public ContractProgress queryContract(String orderNo, String orgCode, String contractId) {
            return contractQuery;
        }

        @Override
        public LedgerApplied applyLedger(LedgerApplication application) {
            calls.add("applyLedger");
            ledger = application;
            if ("REJECTED".equals(ledgerOutcome)) throw new Rejected("MMS01", "委托书缺失");
            if ("UNKNOWN".equals(ledgerOutcome)) throw new Unknown("HTTP_STATUS", null);
            return new LedgerApplied("681201215598657536");
        }

        @Override
        public LedgerProgress queryLedger(String orderNo, String orgCode, String merCupNo) {
            return new LedgerProgress(ledgerValid);
        }

        @Override
        public BindApplied applyBind(BindApplication application) {
            calls.add("applyBind");
            bind = application;
            if ("REJECTED".equals(bindOutcome)) throw new Rejected("MMS02", "合作协议缺失");
            if ("UNKNOWN".equals(bindOutcome)) throw new Unknown("IO", null);
            return new BindApplied("547055274822594560");
        }

        @Override
        public BindProgress queryBind(String orderNo, String orgCode, String merCupNo, String receiverNo,
                String applyId) {
            return bindQuery;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Db db = Db.open();
        final ScriptedChannel channel = new ScriptedChannel();
        final MerchantOnboardingService service;
        final AtomicLong ids = new AtomicLong(80000);

        Fixture() throws Exception {
            ReceiverSensitiveCipher cipher = new ReceiverSensitiveCipher(new SecretKeySpec(new byte[] {
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}, "AES"));
            MerchantOnboardingService.Settings settings = new MerchantOnboardingService.Settings(
                    NOTIFY_BASE, "1950836", "CLOUD_DISTRIBUTION", "654", "T+1",
                    List.of(new MerchantOnboardingChannel.FeeRate("302", "微信", "0.60")),
                    "5995", Map.of("PET_HOSPITAL", "0742"), Map.of("chengdu", "510100"),
                    new BigDecimal("90.50"), PLATFORM_RECEIVER, "宠物平台商家合作协议", "示例科技有限公司");
            service = new MerchantOnboardingService(
                    db.source, ids::incrementAndGet, cipher,
                    assetId -> new AssetContent(assetId, "png", "a.png", PNG),
                    (owner, mediaType, bytes, content, context) -> "777",
                    channel, new JdbcAsyncTaskSubmitter(db.source, ids::incrementAndGet),
                    merchantId -> new MerchantOnboardingFacts(MERCHANT, "5001", "示例宠物医院", "ACTIVE",
                            "PET_HOSPITAL", "chengdu", "APPROVED", "SIGNED"),
                    new LakalaOnboardingNotifications(notifyKeys.getPublic()),
                    settings, Clock.fixed(Instant.parse("2026-11-27T08:00:00Z"), ZoneOffset.UTC));
        }

        OnboardingView view() {
            return service.getByMerchantId(MERCHANT, query());
        }

        SettlementAdminService admin() {
            return new SettlementAdminService(db.source, ids::incrementAndGet,
                    new JdbcAsyncTaskSubmitter(db.source, ids::incrementAndGet), null, PLATFORM_MCH, PLATFORM_RECEIVER);
        }

        DbMerchantPaymentBindings bindings() {
            return new DbMerchantPaymentBindings(db.source, new Binding(PLATFORM_MCH, PLATFORM_TERM, PLATFORM_APP));
        }

        int count(String sql) throws Exception {
            return Integer.parseInt(queryString(sql));
        }

        String queryString(String sql) throws Exception {
            try (Connection c = db.source.getConnection(); Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(sql)) {
                assertTrue(rs.next(), () -> "no row for: " + sql);
                return rs.getString(1);
            }
        }

        @Override
        public void close() throws Exception {
            db.close();
        }
    }

    private static void gateClosed(Fixture f) {
        ApiException closed = assertThrows(ApiException.class, () -> f.bindings().require(MERCHANT, "710302"));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, closed.code());
        assertTrue(closed.getMessage().contains("收单"), closed.getMessage());
        // Merchants without an onboarding record keep the pre-existing fail-closed message.
        ApiException unconfigured = assertThrows(ApiException.class, () -> f.bindings().require("9002", "710302"));
        assertTrue(unconfigured.getMessage().contains("未配置"), unconfigured.getMessage());
    }

    private static SaveOnboardingCommand save(long expectedVersion, String requestId) {
        return profile(expectedVersion, requestId, "线下提供");
    }

    private static SaveOnboardingCommand profile(long expectedVersion, String requestId, String reason) {
        return profile(expectedVersion, requestId, reason, "张三", LEGAL_ID, "13800001234", "李四", "13900000000",
                "张三", "6212260200102400000");
    }

    /** Blank sensitive values (including names) exercise the "keep the stored value" rule. */
    private static SaveOnboardingCommand blankSecrets(long expectedVersion, String requestId, String reason) {
        return profile(expectedVersion, requestId, reason, "", "", "", "", "", "", "");
    }

    private static SaveOnboardingCommand profile(long expectedVersion, String requestId, String reason,
            String larName, String larIdcard, String larMobile, String contactName, String contactMobile,
            String acctName, String acctNo) {
        Map<String, String> attachments = new LinkedHashMap<>();
        attachments.put("FR_ID_CARD_FRONT", "101");
        attachments.put("FR_ID_CARD_BEHIND", "102");
        attachments.put("BANK_CARD", "103");
        attachments.put("BUSINESS_LICENCE", "104");
        attachments.put("MERCHANT_PHOTO", "105");
        attachments.put("SHOPINNER", "106");
        return new SaveOnboardingCommand(
                new CommandContext(requestId, null, OperatorType.PLATFORM_OPERATOR, "7", "ADMIN_WEB"),
                MERCHANT, expectedVersion,
                new MerchantProfileInput("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院",
                        "91110108MA2G7LK368", "2020-01-01", "2040-01-01", larName, larIdcard, "1999-05-29",
                        "2039-05-29", larMobile, contactName, contactMobile),
                new SettlementProfileInput("58", acctName, acctNo, null, null, null, "102100000030",
                        "中国工商银行北京市分行营业部", "102100099996"),
                attachments, "201", reason);
    }

    /** 对私 + 非法人结算人：结算人三项与三张结算人附件齐全。 */
    private static SaveOnboardingCommand settler(long expectedVersion, String requestId, String settlerId,
            String settlerExpDt) {
        Map<String, String> attachments = new LinkedHashMap<>(attachments());
        attachments.put("ID_CARD_FRONT", "301");
        attachments.put("ID_CARD_BEHIND", "302");
        attachments.put("NO_LEGAL_PERSON_SETT_AUTH_LETTER", "303");
        return new SaveOnboardingCommand(
                new CommandContext(requestId, null, OperatorType.PLATFORM_OPERATOR, "7", "ADMIN_WEB"),
                MERCHANT, expectedVersion, merchant(),
                new SettlementProfileInput("58", "张三", "6212260200102400000", "01", settlerId, settlerExpDt,
                        "102100000030", "中国工商银行北京市分行营业部", "102100099996"),
                attachments, "201", "线下提供");
    }

    /** 已有结算人的记录：敏感字段全部留空（仍声明结算人），结算人证件号与有效期保持原值。 */
    private static SaveOnboardingCommand keepSettler(long expectedVersion, String requestId, String reason) {
        Map<String, String> attachments = new LinkedHashMap<>(attachments());
        attachments.put("ID_CARD_FRONT", "301");
        attachments.put("ID_CARD_BEHIND", "302");
        attachments.put("NO_LEGAL_PERSON_SETT_AUTH_LETTER", "303");
        return new SaveOnboardingCommand(
                new CommandContext(requestId, null, OperatorType.PLATFORM_OPERATOR, "7", "ADMIN_WEB"),
                MERCHANT, expectedVersion, blankMerchant(),
                new SettlementProfileInput("58", "", "", "01", "", "",
                        "102100000030", "中国工商银行北京市分行营业部", "102100099996"),
                attachments, "201", reason);
    }

    private static MerchantProfileInput blankMerchant() {
        return new MerchantProfileInput("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院",
                "", "2020-01-01", "2040-01-01", "", "", "1999-05-29", "2039-05-29", "", "", "");
    }

    /** 结算人改回法人本人：withSettlerAttachments=false 时三张结算人附件已移除。 */
    private static SaveOnboardingCommand legalPersonSettler(long expectedVersion, String requestId,
            boolean withSettlerAttachments) {
        Map<String, String> attachments = attachments();
        if (withSettlerAttachments) {
            attachments.put("ID_CARD_FRONT", "301");
            attachments.put("ID_CARD_BEHIND", "302");
            attachments.put("NO_LEGAL_PERSON_SETT_AUTH_LETTER", "303");
        }
        return new SaveOnboardingCommand(
                new CommandContext(requestId, null, OperatorType.PLATFORM_OPERATOR, "7", "ADMIN_WEB"),
                MERCHANT, expectedVersion, merchant(),
                new SettlementProfileInput("58", "", "", null, null, null,
                        "102100000030", "中国工商银行北京市分行营业部", "102100099996"),
                attachments, "201", "线下提供");
    }

    private static MerchantProfileInput merchant() {
        return new MerchantProfileInput("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院",
                "91110108MA2G7LK368", "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29",
                "2039-05-29", "13800001234", "李四", "13900000000");
    }

    private static Map<String, String> attachments() {
        Map<String, String> attachments = new LinkedHashMap<>();
        attachments.put("FR_ID_CARD_FRONT", "101");
        attachments.put("FR_ID_CARD_BEHIND", "102");
        attachments.put("BANK_CARD", "103");
        attachments.put("BUSINESS_LICENCE", "104");
        attachments.put("MERCHANT_PHOTO", "105");
        attachments.put("SHOPINNER", "106");
        return attachments;
    }

    /** Deterministic UUID built from a label, so request ids are stable and distinct per label. */
    private static String id(String label) {
        return String.format("0b9f7fd4-6b3e-4f7d-9c11-%012d",
                (long) (label.hashCode() & 0x7FFFFFFF) * 1009L % 1_000_000_000_000L);
    }

    private static CommandContext command(String label) {
        return new CommandContext(id(label), null, OperatorType.PLATFORM_OPERATOR, "7", "ADMIN_WEB");
    }

    private static QueryContext query() {
        return new QueryContext(null, OperatorType.PLATFORM_OPERATOR, "7");
    }

    private static String ecSigned() {
        return "{\"ecApplyId\":\"" + EC_APPLY + "\",\"ecName\":\"清分结算授权委托书\",\"ecNo\":\"" + EC_NO
                + "\",\"ecStatus\":\"COMPLETED\",\"orgId\":\"1950836\",\"version\":\"1.0\"}";
    }

    private static String contractApproved() {
        return "{\"retUrl\":\"" + NOTIFY_BASE + "\",\"dataPacket\":{\"code\":\"000000\",\"message\":\"成功\",\"data\":{"
                + "\"orgCode\":\"1950836\",\"contractId\":\"" + CONTRACT_ID + "\",\"contractStatus\":\"WAIT_FOR_CONTACT\","
                + "\"contractMemo\":\"\",\"merInnerNo\":\"" + MER_INNER + "\",\"merCupNo\":\"" + MER_CUP + "\","
                + "\"termDatas\":[{\"shopId\":\"431750371322392576\",\"termNo\":\"" + TERM + "\"}]}}}";
    }

    private static String ledgerApproved() {
        return "{\"applyId\":\"681201215598657536\",\"merCupNo\":\"" + MER_CUP + "\",\"merInnerNo\":\"" + MER_INNER
                + "\",\"auditStatus\":\"1\",\"auditStatusText\":\"审核通过\",\"remark\":\"审核通过\"}";
    }

    private static String bindApproved() {
        return "{\"respData\":{\"optType\":\"ADD\",\"applyId\":\"547055274822594560\",\"merCupNo\":\"" + MER_CUP
                + "\",\"merInnerNo\":\"" + MER_INNER + "\",\"receiverNo\":\"" + PLATFORM_RECEIVER + "\","
                + "\"auditStatus\":\"1\",\"auditStatusText\":\"审核通过\",\"remark\":\"通过\"}}";
    }

    /** 1009 three-line signature: timestamp, nonce, raw body, every line terminated. */
    private static Map<String, String> sign(KeyPair keys, byte[] body) throws Exception {
        String timestamp = "1764050000";
        String nonce = "Ab12Cd34Ef56";
        byte[] message = (timestamp + "\n" + nonce + "\n" + new String(body, StandardCharsets.UTF_8) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(message);
        return Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"" + timestamp + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + Base64.getEncoder().encodeToString(signature.sign()) + "\"");
    }

    /** Fresh isolated database holding only the tables the onboarding chain touches. */
    private static final class Db implements AutoCloseable {
        private static final List<String> SCHEMAS = List.of(
                "13-Async-Infra-Schema-v0.1.sql",
                "78-Payment-Settlement-Schema-v0.1.sql",
                "83-Payment-Merchant-Acquiring-Schema-v0.1.sql",
                "84-Payment-Merchant-Onboarding-Schema-v0.1.sql");

        final String name;
        final String baseUrl;
        final String user;
        final String password;
        final DataSource source;

        private Db(String name, String baseUrl, String user, String password, DataSource source) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.user = user;
            this.password = password;
            this.source = source;
        }

        static Db open() throws Exception {
            String url = System.getenv("PAYMENT_CENTER_MYSQL_URL");
            if (url == null || url.isBlank()) url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "MySQL URL env not set");
            String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
            String pass = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", "root"));
            String dbName = "onboarding_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String normalized = url.endsWith("/") ? url : url + "/";
            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(normalized + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(pass);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(normalized + dbName
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&allowMultiQueries=true");
            ds.setUser(user);
            ds.setPassword(pass);
            Path root = repoRoot().resolve("docs/03-database");
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                for (String file : SCHEMAS) {
                    ScriptUtils.executeSqlScript(c, new EncodedResource(
                            new FileSystemResource(root.resolve(file)), StandardCharsets.UTF_8));
                }
                s.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            }
            return new Db(dbName, normalized, user, pass, ds);
        }

        @Override
        public void close() throws Exception {
            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(baseUrl + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(password);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }

        private static String env(String key, String fallback) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static Path repoRoot() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return java.util.Objects.requireNonNull(p);
        }
    }
}
