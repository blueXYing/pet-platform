package com.petplatform.customerservice.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contract 67 feedback-ticket statements; SQL lives in resources/mapper/FeedbackTicketMapper.xml.
 * Ticket rows are owner-scoped projections; the audit event table is append-only; the command
 * binding mirrors SQL14 (supplement 23 §5, module-local table — never another module's).
 */
public interface FeedbackTicketMapper {

    int insertTicket(@Param("id") long id, @Param("userId") long userId, @Param("type") String type,
            @Param("contact") String contact, @Param("content") String content,
            @Param("status") String status);

    int insertEvent(@Param("id") long id, @Param("ticketId") long ticketId,
            @Param("action") String action, @Param("actorType") String actorType,
            @Param("actorId") long actorId, @Param("detail") String detail);

    int insertAttachment(@Param("id") long id, @Param("ticketId") long ticketId,
            @Param("assetId") long assetId, @Param("sortNo") int sortNo);

    TicketRow findTicket(@Param("id") long id, @Param("userId") long userId);

    TicketRow findTicketForOps(@Param("id") long id);

    long countMine(@Param("userId") long userId);

    long countForOps(@Param("status") String status, @Param("type") String type);

    List<TicketRow> selectMinePage(@Param("userId") long userId,
            @Param("limit") int limit, @Param("offset") long offset);

    List<TicketRow> selectOpsPage(@Param("status") String status, @Param("type") String type,
            @Param("limit") int limit, @Param("offset") long offset);

    List<EventRow> selectEvents(@Param("ticketId") long ticketId);

    List<AttachmentRow> selectAttachments(@Param("ticketId") long ticketId);

    int markProcessing(@Param("id") long id, @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion, @Param("actorId") long actorId);

    int markResolved(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId, @Param("reply") String reply);

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion, @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    Binding selectBindingForUpdate(@Param("requestKey") String requestKey);

    int markSucceeded(@Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);

    class TicketRow {
        public Long id, userId, version;
        public String type, contact, content, status, reply;
        public Long replyOperatorId;
        public java.time.LocalDateTime createdAt, updatedAt, repliedAt;
    }

    class EventRow {
        public Long id;
        public String action;
        public java.time.LocalDateTime createdAt;
    }

    class AttachmentRow {
        public Long assetId;
        public Integer sortNo;
    }

    class Binding {
        public Long id;
        public String requestKey, canonicalVersion, paramsSha256, status, receiptJson;
        public byte[] paramsCanonical;
    }
}
