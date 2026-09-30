package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.petplatform.aftersale.api.query.*;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.RouteParty;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.AfterSalePrivateAssetApi.EvidencePrincipal;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AfterSaleAssetAdaptersTest {
    private final AfterSaleAuthorityAdapter authority=mock(AfterSaleAuthorityAdapter.class);
    private final AfterSaleEvidenceAccessApi evidence=mock(AfterSaleEvidenceAccessApi.class);
    private final DataSource source=mock(DataSource.class);
    private final CommandContext context=new CommandContext("51d00faa-5441-449c-bf48-7ed8059d1c68","evidence",OperatorType.USER,"71","MINIAPP");

    @Test void explicitPartyReachesTheBusinessOwnerAndBecomesPartOfTheProof(){
        when(authority.currentSession(context)).thenReturn(new AfterSaleAuthorityAdapter.SessionIdentity("MINIAPP","81",0,"71"));
        when(evidence.proveAccess(eq(context),any(RouteParty.class),eq("11"),eq("12"),eq("13"),same(source))).thenReturn(fact());
        var api=AfterSaleAssetAdapters.authorizer(()->evidence,authority);
        var proof=api.authorize(principal("MERCHANT","81"),"11","12","13",source);
        assertEquals("MERCHANT",proof.party());assertEquals("81",proof.sessionId());assertEquals("71",proof.actorId());
        verify(evidence).proveAccess(context,RouteParty.MERCHANT,"11","12","13",source);
        verify(evidence,never()).proveAccess(any(),anyString(),anyString(),anyString(),any());
    }

    @Test void staleSessionSnapshotOrRevocationFailsBeforeAnyBusinessEvidenceRead(){
        when(authority.currentSession(context)).thenReturn(new AfterSaleAuthorityAdapter.SessionIdentity("MINIAPP","82",0,"71"));
        var api=AfterSaleAssetAdapters.authorizer(()->evidence,authority);
        assertThrows(ApiException.class,()->api.authorize(principal("USER","81"),"11","12","13",source));
        when(authority.currentSession(context)).thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED,"revoked"));
        assertThrows(ApiException.class,()->api.authorize(principal("USER","82"),"11","12","13",source));
        verifyNoInteractions(evidence);
    }

    @Test void explicitPartyCannotFallBackToGenericParticipation(){
        when(authority.currentSession(context)).thenReturn(new AfterSaleAuthorityAdapter.SessionIdentity("MINIAPP","81",0,"71"));
        when(evidence.proveAccess(context,RouteParty.USER,"11","12","13",source)).thenThrow(new ApiException(CommonApiCodes.FORBIDDEN,"not buyer"));
        when(evidence.proveAccess(context,"11","12","13",source)).thenReturn(fact());
        var api=AfterSaleAssetAdapters.authorizer(()->evidence,authority);
        assertThrows(ApiException.class,()->api.authorize(principal("USER","81"),"11","12","13",source));
        verify(evidence,never()).proveAccess(context,"11","12","13",source);
    }

    private EvidencePrincipal principal(String party,String session){return new EvidencePrincipal(context,"MINIAPP",session,0,party);}
    private static AfterSaleEvidenceAccessApi.EvidenceAccess fact(){return new AfterSaleEvidenceAccessApi.EvidenceAccess(
            "11","12","13","72","a".repeat(64),"object-v1","3","21","22","4","authz-current");}
}
