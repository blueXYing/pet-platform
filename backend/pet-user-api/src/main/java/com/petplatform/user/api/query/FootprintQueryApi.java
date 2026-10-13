package com.petplatform.user.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.dto.FootprintItemDTO;

/** Contract 58 footprint read surface (session subject only). */
public interface FootprintQueryApi {

    /** Fixed ordering last_viewed_at DESC, id DESC; page/pageSize follow the platform bounds. */
    PageResult<FootprintItemDTO> listMyFootprints(MyFootprintListQuery query);

    record MyFootprintListQuery(int page, int pageSize, QueryContext context) {}
}
