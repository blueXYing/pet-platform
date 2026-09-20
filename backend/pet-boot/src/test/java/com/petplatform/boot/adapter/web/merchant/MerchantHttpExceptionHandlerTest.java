package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.petplatform.common.CommonApiCodes;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;

class MerchantHttpExceptionHandlerTest {
  @Test
  void dataAccessFailuresRemainFailClosedWithoutWebPersistenceCoupling() {
    var response = new MockHttpServletResponse();

    var body =
        new MerchantHttpExceptionHandler()
            .unavailable(new DataAccessResourceFailureException("sensitive"), response);

    assertEquals(503, response.getStatus());
    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, body.get("code"));
    assertEquals("no-store, private", response.getHeader("Cache-Control"));
  }
}
