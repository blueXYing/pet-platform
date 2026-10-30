package com.petplatform.community.api;

/** Stable error codes for the CCR-COM-001 surface (see contract 73). */
public final class CommunityApiCodes {

    private CommunityApiCodes() {}

    public static final String POST_NOT_FOUND = "COMMUNITY_POST_NOT_FOUND";
    public static final String COMMENT_NOT_FOUND = "COMMUNITY_COMMENT_NOT_FOUND";
    public static final String TOPIC_NOT_FOUND = "COMMUNITY_TOPIC_NOT_FOUND";
    public static final String USER_NOT_FOUND = "COMMUNITY_USER_NOT_FOUND";
    public static final String POST_INVALID = "COMMUNITY_POST_INVALID";
    public static final String COMMENT_INVALID = "COMMUNITY_COMMENT_INVALID";
    public static final String REPORT_INVALID = "COMMUNITY_REPORT_INVALID";
    public static final String MEDIA_INVALID = "COMMUNITY_MEDIA_INVALID";
    public static final String MEDIA_TOO_LARGE = "COMMUNITY_MEDIA_TOO_LARGE";
    public static final String STATE_NOT_ALLOWED = "COMMUNITY_STATE_NOT_ALLOWED";
    public static final String ALREADY_REPORTED = "COMMUNITY_ALREADY_REPORTED";
}
