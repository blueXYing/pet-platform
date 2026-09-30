package com.petplatform.thirdparty.biz;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.petplatform.common.*;
import com.petplatform.thirdparty.api.*;
import com.petplatform.thirdparty.biz.apiimpl.AfterSalePrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.application.port.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AfterSaleEvidenceUnicodeTest {
    @Test void internalMalformedReasonIsRejectedBeforeAuthorizationTransactionOrGrantWrite(){
        DataSource source=mock(DataSource.class);
        var authorization=mock(AfterSaleAssetReadAuthorizer.class);
        var objects=mock(PrivateObjectStore.class);
        var keys=mock(PrivateAssetGrantKeyProvider.class);
        var reasons=mock(PrivateAssetReasonProtector.class);
        var api=new AfterSalePrivateAssetApiImpl(source,()->99L,authorization,objects,keys,reasons,mock(PrivateAssetWatermarkRenderer.class));
        var context=new CommandContext("e052d00f-a544-4144-9b5f-a6b7bbb81682","unicode",OperatorType.USER,"71","MINIAPP");
        for(char surrogate:new char[]{(char)0xD800,(char)0xDC00}){
            var error=assertThrows(ApiException.class,()->api.issue(new AfterSalePrivateAssetApi.Issue(context,"11","12","13","Read evidence "+surrogate)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,error.code());
        }
        verifyNoInteractions(source,authorization,objects,keys,reasons);
    }
}
