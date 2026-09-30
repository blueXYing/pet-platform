package com.petplatform.boot.adapter.web.aftersale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.*;
import com.petplatform.thirdparty.api.AfterSalePrivateAssetApi.*;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP boundary tests with controlled owner APIs; real authority/database coverage is separate. */
class AfterSaleEvidenceHttpTest {
    private static final String REQUEST="9fc40381-a51b-4611-965c-bab62e9364d8";
    private static final String TOKEN="x".repeat(43);
    private static final String UPLOAD="/api/v1/c/aftersale-evidence-assets";
    private static final String ISSUE="/api/v1/c/aftersales/101/evidence-batches/102/assets/103/read-grants";
    private static final String READ="/api/v1/c/aftersale-evidence-read-grants/"+TOKEN;
    private static final byte[] PNG={(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
    private final UserAuthService users=mock(UserAuthService.class);
    private final AdminSessionQueryApi admins=mock(AdminSessionQueryApi.class);
    private final PrivateAssetApi uploads=mock(PrivateAssetApi.class);
    private final AfterSalePrivateAssetApi evidence=mock(AfterSalePrivateAssetApi.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new AfterSaleEvidenceController(uploads,evidence,users,admins)).build();

    @Test void uploadDerivesOwnerAndFixedPurposeAndRechecksSession()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("ACTIVE"));
        when(uploads.upload(any())).thenAnswer(invocation->{
            UploadPrivateAssetCommand c=invocation.getArgument(0);
            assertEquals("701",c.ownerUserId());assertEquals("701",c.context().operatorId());
            assertEquals("AFTERSALE_EVIDENCE",c.purpose());assertEquals(OperatorType.USER,c.context().operatorType());
            assertEquals("image/png",c.declaredMediaType());assertArrayEquals(PNG,c.content().readAllBytes());
            return new UploadPrivateAssetResult("103",true,PrivateAssetStatus.READY,"a".repeat(64),"image/png",PNG.length);
        });
        mvc.perform(multipart(UPLOAD).file(png()).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.assetId").value("103"))
                .andExpect(header().string("Cache-Control","no-store, private"));
        verify(users,times(2)).resolveSession("buyer");
    }

    @Test void multipartCannotInjectPurposeOwnerOrSecondFileAndFrozenCannotWrite()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("FROZEN"));
        mvc.perform(multipart(UPLOAD).file(png()).param("purpose","MERCHANT_APPLICATION_MATERIAL").header("X-Request-Id",REQUEST))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(UPLOAD).file(png()).file(png()).header("X-Request-Id",REQUEST)).andExpect(status().isBadRequest());
        mvc.perform(multipart(UPLOAD).file(png()).param("ownerUserId","999").header("X-Request-Id",REQUEST)).andExpect(status().isBadRequest());
        mvc.perform(multipart(UPLOAD).file(png()).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_FROZEN"));
        verifyNoInteractions(uploads);
    }

    @Test void uploadRejectsSignatureMismatchAndSessionRevocationBeforeReturningReceipt()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("ACTIVE"));
        mvc.perform(multipart(UPLOAD).file(new MockMultipartFile("file","e.jpg","image/jpeg",PNG))
                .header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(uploads);
        when(uploads.upload(any())).thenReturn(new UploadPrivateAssetResult("103",true,PrivateAssetStatus.READY,"a".repeat(64),"image/png",PNG.length));
        when(users.resolveSession("buyer")).thenReturn(session("ACTIVE")).thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED,"revoked"));
        mvc.perform(multipart(UPLOAD).file(png()).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store, private"));
    }

    @Test void issueUsesExplicitRouteAndRejectsIdentityFieldsDuplicateJsonAndQueryParameters()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("ACTIVE"));
        when(evidence.issue(any())).thenReturn(new Grant(TOKEN,OffsetDateTime.parse("2026-09-30T00:05:00.000Z")));
        for(String route:new String[]{"c","merchant"}){
            mvc.perform(post(ISSUE.replace("/c/","/"+route+"/")).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST)
                    .contentType("application/json").content("{\"reason\":\"Inspect evidence\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.readUrl").value("/api/v1/"+route+"/aftersale-evidence-read-grants/"+TOKEN));
        }
        var commands=ArgumentCaptor.forClass(Issue.class);verify(evidence,times(2)).issue(commands.capture());
        assertEquals("USER",commands.getAllValues().get(0).principal().party());assertEquals("MERCHANT",commands.getAllValues().get(1).principal().party());
        for(var command:commands.getAllValues()){
            assertEquals("MINIAPP",command.principal().audience());assertEquals("801",command.principal().sessionId());
            assertEquals("701",command.context().operatorId());assertEquals(REQUEST,command.context().requestId());
        }
        clearInvocations(evidence);
        for(String body:new String[]{"{\"reason\":\"x\",\"party\":\"OPS\"}","{\"reason\":\"x\",\"sessionId\":\"99\"}",
                "{\"reason\":\"x\",\"reason\":\"y\"}","{\"reason\":3}","{\"reason\":\" \"}","{\"reason\":\"x\"} {}"}){
            mvc.perform(post(ISSUE).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(ISSUE+"?ownerUserId=99").header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST)
                .contentType("application/json").content("{\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(evidence);
    }

    @Test void consumeNeverCachesOrReplaysContentAndPreservesTypedAudience()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("FROZEN"));
        when(evidence.consume(any())).thenReturn(new PrivateAssetContent(PNG,"image/png","a".repeat(64),PNG.length))
                .thenThrow(new ApiException(PrivateAssetApiCodes.GRANT_GONE,"already consumed"));
        mvc.perform(get(READ).header("Authorization","Bearer buyer")).andExpect(status().isOk()).andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get(READ).header("Authorization","Bearer buyer")).andExpect(status().isGone())
                .andExpect(header().string("Cache-Control","no-store, private"));
        var commands=ArgumentCaptor.forClass(Consume.class);verify(evidence,times(2)).consume(commands.capture());
        assertTrue(commands.getAllValues().stream().allMatch(c->"USER".equals(c.principal().party())));
        assertNotEquals(commands.getAllValues().get(0).context().requestId(),commands.getAllValues().get(1).context().requestId());
    }

    @Test void malformedUnicodeCannotAliasAnExistingRequestReasonButPairedEmojiRemainsValid()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("ACTIVE"));
        when(evidence.issue(any())).thenReturn(new Grant(TOKEN,OffsetDateTime.parse("2026-09-30T00:05:00.000Z")));
        mvc.perform(post(ISSUE).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST)
                .contentType("application/json").content("{\"reason\":\"Read evidence ?\"}")).andExpect(status().isOk());
        for(String escaped:new String[]{"\\uD800","\\uDC00"}){
            mvc.perform(post(ISSUE).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST)
                    .contentType("application/json").content("{\"reason\":\"Read evidence "+escaped+"\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(CommonApiCodes.INVALID_ARGUMENT));
        }
        verify(evidence,times(1)).issue(any());
        mvc.perform(post(ISSUE).header("Authorization","Bearer buyer").header("X-Request-Id","9ac40381-a51b-4611-965c-bab62e9364d8")
                .contentType("application/json").content("{\"reason\":\"Read evidence \\uD83D\\uDE00\"}")).andExpect(status().isOk());
        var commands=ArgumentCaptor.forClass(Issue.class);verify(evidence,times(2)).issue(commands.capture());
        assertEquals("Read evidence "+new String(Character.toChars(0x1F600)),commands.getAllValues().get(1).reason());
    }

    @Test void invalidReadShapeCannotConsumeGrant()throws Exception{
        when(users.resolveSession("buyer")).thenReturn(session("ACTIVE"));
        mvc.perform(get(READ+"?download=true").header("Authorization","Bearer buyer")).andExpect(status().isBadRequest());
        mvc.perform(get(READ).content("x").header("Authorization","Bearer buyer")).andExpect(status().isBadRequest());
        mvc.perform(get(READ+"x").header("Authorization","Bearer buyer")).andExpect(status().isBadRequest());
        verifyNoInteractions(evidence);
    }

    @Test void missingOrDuplicateIdentityAndWriteRequestHeadersCannotReachOwnerApis()throws Exception{
        for(String route:new String[]{"c","merchant","admin"}){
            String issue=ISSUE.replace("/c/","/"+route+"/"),read=READ.replace("/c/","/"+route+"/");
            mvc.perform(post(issue).header("X-Request-Id",REQUEST).contentType("application/json").content("{\"reason\":\"Read evidence\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(issue).header("Authorization","Bearer buyer","Bearer other").header("X-Request-Id",REQUEST)
                    .contentType("application/json").content("{\"reason\":\"Read evidence\"}")).andExpect(status().isUnauthorized());
            mvc.perform(get(read)).andExpect(status().isUnauthorized());
            mvc.perform(get(read).header("Authorization","Bearer buyer","Bearer other")).andExpect(status().isUnauthorized());
            mvc.perform(post(issue).header("Authorization","Bearer buyer").contentType("application/json").content("{\"reason\":\"Read evidence\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(issue).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST,REQUEST)
                    .contentType("application/json").content("{\"reason\":\"Read evidence\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart(UPLOAD).file(png()).header("X-Request-Id",REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(UPLOAD).file(png()).header("Authorization","Bearer buyer","Bearer other").header("X-Request-Id",REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(UPLOAD).file(png()).header("Authorization","Bearer buyer")).andExpect(status().isBadRequest());
        mvc.perform(multipart(UPLOAD).file(png()).header("Authorization","Bearer buyer").header("X-Request-Id",REQUEST,REQUEST))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users,admins,uploads,evidence);
    }

    private static MockMultipartFile png(){return new MockMultipartFile("file","e.png","image/png",PNG);}
    private static UserAuthService.MiniSessionView session(String status){return new UserAuthService.MiniSessionView("801","701",Instant.parse("2026-10-01T00:00:00Z"),"138****0000",status);}
}
