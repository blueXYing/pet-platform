package com.petplatform.aftersale.biz.application;

import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.common.*;
import java.util.*;

/** One complete immutable catalog is shared by reads and new-create validation. */
public final class AfterSaleCatalog {
    private AfterSaleCatalog() {}
    public static Options requireValid(Options options) {
        if(options==null)throw unavailable();
        requireList(options.typeOptions());requireList(options.demandOptions());return options;
    }
    private static void requireList(List<Option> options) {
        if(options==null||options.isEmpty()||options.size()>100)throw unavailable();
        String previous=null;
        for(var option:options) {
            if(option==null||option.code()==null||!option.code().matches("[A-Z][A-Z0-9_]{0,63}"))throw unavailable();
            String label=option.label();
            if(label==null||label.isEmpty()||whitespace(label.charAt(0))||whitespace(label.charAt(label.length()-1))||label.codePointCount(0,label.length())>64
                    ||label.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw unavailable();
            if(previous!=null&&previous.compareTo(option.code())>=0)throw unavailable();previous=option.code();
        }
    }
    /** Same edge whitespace as ECMAScript String.trim(), including NBSP and BOM. */
    private static boolean whitespace(char c){return c=='\t'||c=='\n'||c=='\u000B'||c=='\f'||c=='\r'||c==' '
            ||c=='\u00A0'||c=='\u1680'||c>='\u2000'&&c<='\u200A'||c=='\u2028'||c=='\u2029'
            ||c=='\u202F'||c=='\u205F'||c=='\u3000'||c=='\uFEFF';}
    public static void requireCodes(Options options,String type,String demand) {
        requireValid(options);
        if(options.typeOptions().stream().noneMatch(o->o.code().equals(type))
                ||options.demandOptions().stream().noneMatch(o->o.code().equals(demand)))
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Aftersale reason unavailable");
    }
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Aftersale catalog unavailable");}
}
