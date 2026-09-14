package com.petplatform.admin.api.query;

import com.petplatform.admin.api.dto.AdminSessionView;

/** Local trusted adapter boundary. Never log the argument or treat a client principal as proof. */
public interface AdminSessionQueryApi {
  AdminSessionView resolveSession(String accessToken);
}
