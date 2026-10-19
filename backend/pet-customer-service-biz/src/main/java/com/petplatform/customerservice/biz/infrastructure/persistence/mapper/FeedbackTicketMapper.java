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

    TicketRow findTicket(@Param("id") long id, @Param("userId") long userId);

    long countMine(@Param("userId") long userId);

    List<TicketRow> selectMinePage(@Param("userId") long userId,
            @Param("limit") int limit, @Param("offset") long offset);

    List<EventRow> selectEvents(@Param("ticketId") long ticketId);

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion, @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    Binding selectBindingForUpdate(@Param("requestKey") String requestKey);

    int markSucceeded(@Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);

    class TicketRow {
        public Long id, userId, version;
        public String type, contact, content, status;
        public java.time.LocalDateTime createdAt, updatedAt;
    }

    class EventRow {
        public Long id;
        public String action;
        public java.time.LocalDateTime createdAt;
    }

    class Binding {
        public Long id;
        public String requestKey, canonicalVersion, paramsSha256, status, receiptJson;
        public byte[] paramsCanonical;
    }
}
