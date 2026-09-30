package com.petplatform.boot.adapter.web.aftersale;

import static com.petplatform.boot.adapter.web.aftersale.EvidenceHttpSupport.*;

import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.*;
import com.petplatform.thirdparty.api.AfterSalePrivateAssetApi.*;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import com.petplatform.user.biz.application.UserAuthService;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.*;

/** Contract51 private evidence HTTP. All reads use a route-bound, current-session typed grant. */
@RestController
@ConditionalOnProperty(name="pet.aftersale.http.enabled",havingValue="true")
public final class AfterSaleEvidenceController {
    private final PrivateAssetApi uploads;
    private final AfterSalePrivateAssetApi evidence;
    private final EvidenceHttpSupport support;

    public AfterSaleEvidenceController(PrivateAssetApi uploads,AfterSalePrivateAssetApi evidence,
            UserAuthService users,AdminSessionQueryApi admins){
        this.uploads=Objects.requireNonNull(uploads);this.evidence=Objects.requireNonNull(evidence);
        this.support=new EvidenceHttpSupport(users,admins);
    }

    @PostMapping(value="/api/v1/c/aftersale-evidence-assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> upload(@RequestPart("file")MultipartFile file,HttpServletRequest request,HttpServletResponse response)throws IOException{
        privateHeaders(response);
        if(!(request instanceof MultipartHttpServletRequest multipart)||!multipart.getParameterMap().isEmpty()
                ||!multipart.getMultiFileMap().keySet().equals(Set.of("file"))||multipart.getFiles("file").size()!=1||file.isEmpty())throw invalid();
        if(file.getSize()>MAX_UPLOAD_BYTES)throw new EvidenceTooLarge();
        if(file.getSize()<1)throw invalid();
        var principal=support.principal(request,"c",true,true);
        UploadPrivateAssetResult result;
        try(var content=new PushbackInputStream(file.getInputStream(),8)){
            result=uploads.upload(new UploadPrivateAssetCommand(principal.context().operatorId(),AfterSalePrivateAssetApi.PURPOSE,
                    mediaType(content,file.getContentType()),file.getSize(),content,principal.context()));
        }
        support.requireCurrent(request,principal,true);
        if(result==null||result.assetId()==null||result.status()!=PrivateAssetStatus.READY||result.objectSha256()==null
                ||!result.objectSha256().matches("[0-9a-f]{64}")||!IMAGES.contains(result.mediaType())||result.bytes()<1||result.bytes()>MAX_UPLOAD_BYTES)throw unavailable();
        try{id(result.assetId());}catch(ApiException badReceipt){throw unavailable();}
        response.setStatus(result.created()?201:200);
        var data=new LinkedHashMap<String,Object>();data.put("assetId",result.assetId());data.put("status",result.status().name());
        data.put("objectSha256",result.objectSha256());data.put("mediaType",result.mediaType());data.put("bytes",result.bytes());return envelope(data,request);
    }

    @PostMapping(value="/api/v1/{audience:c|merchant|admin}/aftersales/{afterSaleId}/evidence-batches/{batchId}/assets/{assetId}/read-grants",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> issue(@PathVariable String audience,@PathVariable String afterSaleId,@PathVariable String batchId,
            @PathVariable String assetId,@RequestBody String json,HttpServletRequest request,HttpServletResponse response){
        privateHeaders(response);noParameters(request);String reason=reason(json);
        var principal=support.principal(request,audience,false,true);
        var grant=evidence.issue(new Issue(principal,id(afterSaleId),id(batchId),id(assetId),reason));
        if(grant==null||grant.token()==null||!grant.token().matches("[A-Za-z0-9_-]{43}")||grant.expiresAt()==null
                ||grant.expiresAt().getNano()%1_000_000!=0)throw unavailable();
        support.requireCurrent(request,principal,false);
        var data=new LinkedHashMap<String,Object>();data.put("readUrl","/api/v1/"+audience+"/aftersale-evidence-read-grants/"+grant.token());
        data.put("expiresAt",time(grant.expiresAt()));return envelope(data,request);
    }

    @GetMapping("/api/v1/{audience:c|merchant|admin}/aftersale-evidence-read-grants/{token}")
    void consume(@PathVariable String audience,@PathVariable String token,HttpServletRequest request,HttpServletResponse response)throws IOException{
        privateHeaders(response);noReadBody(request);
        var principal=support.principal(request,audience,false,false);
        var result=evidence.consume(new Consume(principal,token(token)));
        byte[] content=result==null?null:result.content();
        if(result==null||content==null||content.length<1||content.length>MAX_RENDERED_BYTES||result.bytes()!=content.length
                ||!IMAGES.contains(result.mediaType())||result.objectSha256()==null||!result.objectSha256().matches("[0-9a-f]{64}"))throw unavailable();
        support.requireCurrent(request,principal,false);
        response.setStatus(200);response.setHeader("Content-Disposition","attachment; filename=\"aftersale-evidence\"");
        response.setContentType(result.mediaType());response.setContentLengthLong(content.length);response.getOutputStream().write(content);
    }

    @ExceptionHandler(ApiException.class)
    Map<String,Object> api(ApiException failure,HttpServletRequest request,HttpServletResponse response){
        int status=switch(failure.code()){
            case CommonApiCodes.INVALID_ARGUMENT->400;
            case CommonApiCodes.UNAUTHORIZED,"ADMIN_UNAUTHORIZED"->401;
            case CommonApiCodes.FORBIDDEN,"USER_FROZEN"->403;
            case CommonApiCodes.NOT_FOUND,"AFTERSALE_NOT_FOUND","ORDER_NOT_FOUND"->404;
            case CommonApiCodes.CONFLICT,CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,PrivateAssetApiCodes.ASSET_NOT_READY->409;
            case PrivateAssetApiCodes.GRANT_GONE->410;
            case PrivateAssetApiCodes.ASSET_REJECTED->422;
            case CommonApiCodes.RATE_LIMITED->429;
            case CommonApiCodes.DEPENDENCY_UNAVAILABLE->503;
            default->503;
        };
        return error(status,failure.code(),failure.getMessage(),request,response);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingRequestValueException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class,MultipartException.class,
            MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    Map<String,Object> invalidRequest(Exception ignored,HttpServletRequest request,HttpServletResponse response){
        return error(400,CommonApiCodes.INVALID_ARGUMENT,"Invalid evidence request",request,response);
    }
    @ExceptionHandler({EvidenceTooLarge.class,MaxUploadSizeExceededException.class})
    Map<String,Object> tooLarge(Exception ignored,HttpServletRequest request,HttpServletResponse response){
        return error(413,CommonApiCodes.INVALID_ARGUMENT,"Evidence image exceeds 10MiB",request,response);
    }
    @ExceptionHandler(UnsupportedEvidenceMedia.class)
    Map<String,Object> unsupported(Exception ignored,HttpServletRequest request,HttpServletResponse response){
        return error(415,CommonApiCodes.INVALID_ARGUMENT,"Evidence must be a JPEG or PNG image",request,response);
    }
    @ExceptionHandler({org.springframework.core.NestedRuntimeException.class,IOException.class})
    Map<String,Object> unavailableRequest(Exception ignored,HttpServletRequest request,HttpServletResponse response){
        return error(503,CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Evidence dependency unavailable",request,response);
    }
}
