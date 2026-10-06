package com.petplatform.boot.config;

import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.aftersale.biz.application.*;
import com.petplatform.common.*;
import java.util.*;

/** Approved configuration only. Incomplete labels close both catalog and new creation. */
final class ConfiguredAfterSaleReasonPolicy implements AfterSalePorts.ReasonPolicy {
    private final Options snapshot;
    ConfiguredAfterSaleReasonPolicy(Set<String> types,Map<String,String> typeLabels,
            Set<String> demands,Map<String,String> demandLabels) {
        Options candidate=null;
        try {
            if(!types.equals(typeLabels.keySet())||!demands.equals(demandLabels.keySet()))throw unavailable();
            candidate=AfterSaleCatalog.requireValid(new Options(options(types,typeLabels),options(demands,demandLabels)));
        }catch(RuntimeException invalid){candidate=null;}
        snapshot=candidate;
    }
    private static List<Option> options(Set<String> codes,Map<String,String> labels){return codes.stream().sorted().map(code->new Option(code,labels.get(code))).toList();}
    @Override public Options options(){if(snapshot==null)throw unavailable();return snapshot;}
    @Override public void requireCodes(String type,String demand){AfterSaleCatalog.requireCodes(options(),type,demand);}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Aftersale catalog unavailable");}
}
