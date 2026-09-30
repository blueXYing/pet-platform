package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.common.ApiException;
import com.petplatform.thirdparty.api.AfterSalePrivateAssetApi.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AfterSalePrivateEvidenceAcceptanceTest {
    @Test void buyerAndCurrentOperatorReceiveOnlySingleUseWatermarkedContent() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var order=f.verifiedOrder();var asset=f.assets.upload(AfterSaleFixture.BUYER);
            f.identity.asUser(AfterSaleFixture.BUYER);
            var created=f.aftersales.create(f.createCommand(order,List.of(asset),null));
            var issue=new Issue(f.user(AfterSaleFixture.BUYER),created.afterSaleId(),created.evidenceBatchId(),asset,"Review my private service evidence");
            var grant=f.privateAssets.issue(issue);
            assertEquals(grant,f.privateAssets.issue(issue));
            assertTrue(grant.token().matches("[A-Za-z0-9_-]{43}"));
            assertFalse(grant.token().contains("/"));
            var content=f.privateAssets.consume(new Consume(f.user(AfterSaleFixture.BUYER),grant.token()));
            assertTrue(content.bytes()>0);assertEquals("image/png",content.mediaType());
            assertThrows(ApiException.class,()->f.privateAssets.consume(new Consume(f.user(AfterSaleFixture.BUYER),grant.token())));
            f.identity.asAdmin();
            var operator=f.privateAssets.issue(new Issue(f.admin(),created.afterSaleId(),created.evidenceBatchId(),asset,"Review evidence for case resolution"));
            assertTrue(f.privateAssets.consume(new Consume(f.admin(),operator.token())).bytes()>0);
        }
    }

    @Test void tokenCannotBeLentToAnotherUserOrOperatorAndOriginalHolderRetainsIt() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var order=f.verifiedOrder();var asset=f.assets.upload(AfterSaleFixture.BUYER);
            var created=f.aftersales.create(f.createCommand(order,List.of(asset),null));
            var grant=f.privateAssets.issue(new Issue(f.user(AfterSaleFixture.BUYER),created.afterSaleId(),created.evidenceBatchId(),asset,"Review submitted evidence"));
            f.identity.asUser(AfterSaleFixture.OTHER);
            assertThrows(ApiException.class,()->f.privateAssets.issue(new Issue(f.user(AfterSaleFixture.OTHER),created.afterSaleId(),created.evidenceBatchId(),asset,"Unrelated account cannot view")));
            assertThrows(ApiException.class,()->f.privateAssets.consume(new Consume(f.user(AfterSaleFixture.OTHER),grant.token())));
            f.identity.asAdmin();
            assertThrows(ApiException.class,()->f.privateAssets.consume(new Consume(f.admin(),grant.token())));
            f.identity.asUser(AfterSaleFixture.BUYER);
            assertTrue(f.privateAssets.consume(new Consume(f.user(AfterSaleFixture.BUYER),grant.token())).bytes()>0);
        }
    }

    @Test void revokedSessionCannotIssueReplayOrConsumeOutstandingGrant() throws Exception {
        for(boolean admin:List.of(false,true))try(var f=new AfterSaleFixture()) {
            var order=f.verifiedOrder();var asset=f.assets.upload(AfterSaleFixture.BUYER);
            var created=f.aftersales.create(f.createCommand(order,List.of(asset),null));
            if(admin)f.identity.asAdmin();else f.identity.asUser(AfterSaleFixture.BUYER);
            var context=admin?f.admin():f.user(AfterSaleFixture.BUYER);
            var issue=new Issue(context,created.afterSaleId(),created.evidenceBatchId(),asset,"Authorized evidence investigation");
            var grant=f.privateAssets.issue(issue);
            if(admin)f.identity.revokeAdmin();else f.identity.revokeUser(AfterSaleFixture.BUYER);
            assertThrows(ApiException.class,()->f.privateAssets.issue(issue));
            assertThrows(ApiException.class,()->f.privateAssets.consume(new Consume(context,grant.token())));
        }
    }

    @Test void assetIdAloneDoesNotProveCaseEvidenceMembership() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var order=f.verifiedOrder();var attached=f.assets.upload(AfterSaleFixture.BUYER);
            var unattached=f.assets.upload(AfterSaleFixture.BUYER);
            var created=f.aftersales.create(f.createCommand(order,List.of(attached),null));
            assertThrows(ApiException.class,()->f.privateAssets.issue(new Issue(f.user(AfterSaleFixture.BUYER),created.afterSaleId(),created.evidenceBatchId(),unattached,"Attempt to substitute an unattached private asset")));
        }
    }
}
