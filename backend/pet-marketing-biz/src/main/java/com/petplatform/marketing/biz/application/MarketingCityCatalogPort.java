package com.petplatform.marketing.biz.application;

/** Open-city directory port; boot adapts the merchant application city catalog. */
public interface MarketingCityCatalogPort {
    boolean isOpen(String cityCode);
}
