package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Joined immutable agreement-version/acceptance projection owned by merchant-biz. */
public final class MerchantAgreementDocumentEntity {
    private Long versionId;
    private String agreementVersion;
    private String content;
    private String contentSha256;
    private String acceptedContentSha256;
    private LocalDateTime acceptedAt;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getAgreementVersion() { return agreementVersion; }
    public void setAgreementVersion(String agreementVersion) { this.agreementVersion = agreementVersion; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getAcceptedContentSha256() { return acceptedContentSha256; }
    public void setAcceptedContentSha256(String acceptedContentSha256) {
        this.acceptedContentSha256 = acceptedContentSha256;
    }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
}
