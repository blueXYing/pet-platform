-- AUTH-001 accepted A/B1/C mapping. Isolated opt-in migration only; no default account.
-- UTC DATETIME(3); opaque secrets/passwords never stored in plaintext.
CREATE TABLE admin_account (
 id BIGINT NOT NULL PRIMARY KEY, account_display VARCHAR(128) NOT NULL,
 account_lookup VARBINARY(528) NOT NULL UNIQUE, display_name VARCHAR(64) NOT NULL,
 password_hash VARCHAR(512) NOT NULL, credential_version BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', session_generation BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0, last_login_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 CHECK(id>0 AND credential_version>=0 AND session_generation>=0 AND version>=0),
 CHECK(status IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_role (
 id BIGINT NOT NULL PRIMARY KEY, role_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 display_name VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', version BIGINT NOT NULL DEFAULT 0,
 CHECK(id>0 AND version>=0), CHECK(status IN ('ENABLED','DISABLED')),
 CHECK(role_code IN ('CONTENT_EDITOR','REVIEWER','OPERATIONS_ADMIN','FINANCE_READER','AUDIT_READER','PLATFORM_SUPER_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_role_action (
 role_id BIGINT NOT NULL, action_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL, PRIMARY KEY(role_id,action_code), FOREIGN KEY(role_id) REFERENCES admin_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_account_role (
 account_id BIGINT NOT NULL, role_id BIGINT NOT NULL, granted_by BIGINT NOT NULL, granted_at DATETIME(3) NOT NULL,
 PRIMARY KEY(account_id,role_id), FOREIGN KEY(account_id) REFERENCES admin_account(id), FOREIGN KEY(role_id) REFERENCES admin_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_extra_grant (
 account_id BIGINT NOT NULL, action_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 granted_by BIGINT NOT NULL, granted_at DATETIME(3) NOT NULL, PRIMARY KEY(account_id,action_code),
 FOREIGN KEY(account_id) REFERENCES admin_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_account_scope (
 account_id BIGINT NOT NULL PRIMARY KEY, mode VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(account_id) REFERENCES admin_account(id), CHECK(mode IN ('ALL','CITY','MERCHANT')), CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_scope_city (
 account_id BIGINT NOT NULL, city_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY(account_id,city_code), FOREIGN KEY(account_id) REFERENCES admin_account_scope(account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_scope_merchant (
 account_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL, PRIMARY KEY(account_id,merchant_id),
 FOREIGN KEY(account_id) REFERENCES admin_account_scope(account_id), CHECK(merchant_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_authz_revision (
 id INT NOT NULL PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0,
 recovery_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CHECK(id=1 AND revision>=0)
) ENGINE=InnoDB;
CREATE TABLE admin_auth_attempt (
 id BIGINT NOT NULL PRIMARY KEY, secret_digest BINARY(32) NOT NULL UNIQUE, binding_digest BINARY(32) NOT NULL,
 purpose VARCHAR(32) NOT NULL DEFAULT 'ADMIN_LOGIN', status VARCHAR(16) NOT NULL DEFAULT 'PROVE_IDENTITY',
 expires_at DATETIME(3) NOT NULL, account_id BIGINT NULL, credential_version BIGINT NULL,
 completed_result_id BIGINT NULL, created_at DATETIME(3) NOT NULL,
 source_ip_digest BINARY(32) NOT NULL, risk_lookup_digest BINARY(32) NULL, CHECK(id>0), CHECK(purpose='ADMIN_LOGIN'),
 CHECK(status IN ('PROVE_IDENTITY','COMPLETED')), FOREIGN KEY(account_id) REFERENCES admin_account(id)
) ENGINE=InnoDB;
CREATE TABLE admin_captcha (
 id BIGINT NOT NULL PRIMARY KEY, attempt_id BIGINT NOT NULL,
 answer_mac BINARY(32) NOT NULL, mac_key_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
 image_png MEDIUMBLOB NOT NULL,
 challenge_created_at DATETIME(3) NOT NULL, challenge_expires_at DATETIME(3) NOT NULL, challenge_consumed_at DATETIME(3) NULL,
 failure_count INT NOT NULL DEFAULT 0, proof_digest BINARY(32) NULL UNIQUE,
 proof_issued_at DATETIME(3) NULL, proof_expires_at DATETIME(3) NULL, proof_consumed_at DATETIME(3) NULL,
 INDEX idx_captcha_attempt(attempt_id), FOREIGN KEY(attempt_id) REFERENCES admin_auth_attempt(id),
 CHECK(id>0 AND failure_count>=0),
 CHECK((proof_digest IS NULL AND proof_issued_at IS NULL AND proof_expires_at IS NULL) OR
       (proof_digest IS NOT NULL AND proof_issued_at IS NOT NULL AND proof_expires_at IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE admin_web_session (
 id BIGINT NOT NULL PRIMARY KEY, account_id BIGINT NOT NULL, token_digest BINARY(32) NOT NULL UNIQUE,
 generation BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', issued_at DATETIME(3) NOT NULL,
 last_interactive_at DATETIME(3) NOT NULL, idle_expires_at DATETIME(3) NOT NULL, revoked_at DATETIME(3) NULL,
 INDEX idx_session_account(account_id), FOREIGN KEY(account_id) REFERENCES admin_account(id),
 CHECK(id>0 AND generation>=0), CHECK(status IN ('ACTIVE','REVOKED'))
) ENGINE=InnoDB;
CREATE TABLE admin_auth_command (
 id BIGINT NOT NULL PRIMARY KEY, scope_key VARBINARY(128) NOT NULL,
 attempt_id BIGINT NULL, session_id BIGINT NULL, namespace VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id VARBINARY(36) NOT NULL, canonical_version INT NOT NULL DEFAULT 1,
 parameter_mac BINARY(32) NOT NULL, mac_key_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'RESERVED', result_kind VARCHAR(32) NULL, result_id BIGINT NULL,
 execution_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, cache_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 completed_at DATETIME(3) NULL, receipt_window_anchor_at DATETIME(3) NULL, secret_expires_at DATETIME(3) NULL,
 result_expires_at DATETIME(3) NULL, result_version INT NOT NULL DEFAULT 1, failure_counted BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_auth_command(scope_key,namespace,request_id), CHECK(id>0 AND OCTET_LENGTH(request_id)=36),
 CHECK((attempt_id IS NULL)<>(session_id IS NULL)), CHECK(state IN ('RESERVED','SUCCEEDED')),
 CHECK(state='RESERVED' OR (result_kind IS NOT NULL AND completed_at IS NOT NULL)),
 CHECK((cache_ref IS NULL AND receipt_window_anchor_at IS NULL AND secret_expires_at IS NULL) OR
       (cache_ref IS NOT NULL AND receipt_window_anchor_at IS NOT NULL AND secret_expires_at IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE admin_attempt_creation (
 namespace VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id VARBINARY(36) NOT NULL, attempt_id BIGINT NOT NULL UNIQUE, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(namespace,request_id), FOREIGN KEY(attempt_id) REFERENCES admin_auth_attempt(id),
 CHECK(namespace='ADMIN_LOGIN_CREATE' AND OCTET_LENGTH(request_id)=36)
) ENGINE=InnoDB;
CREATE TABLE admin_login_failure (
 lookup_digest BINARY(32) NOT NULL PRIMARY KEY, window_start DATETIME(3) NOT NULL,
 count INT NOT NULL DEFAULT 0, locked_until DATETIME(3) NULL, version BIGINT NOT NULL DEFAULT 0,
 CHECK(count>=0 AND version>=0)
) ENGINE=InnoDB;
CREATE TABLE admin_audit_intent (
 id BIGINT NOT NULL PRIMARY KEY, actor_id BIGINT NULL, actor_reference VARCHAR(256) NOT NULL, attempt_id BIGINT NULL,
 action_code VARCHAR(64) CHARACTER SET ascii NOT NULL, resource_id BIGINT NULL,
 request_id VARBINARY(36) NULL, trace_ref VARCHAR(64) CHARACTER SET ascii NOT NULL,
 outcome VARCHAR(16) NOT NULL, reason VARCHAR(500) NOT NULL,
 occurred_at DATETIME(3) NOT NULL, authz_version VARCHAR(128) CHARACTER SET ascii NULL,
 delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL, delivered_at DATETIME(3) NULL,
 INDEX idx_audit_pending(delivery_state,next_attempt_at), CHECK(id>0 AND attempt_count>=0),
 CHECK(delivery_state IN ('PENDING','DELIVERED')), CHECK(outcome IN ('ALLOWED','DENIED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_bootstrap (
 id INT NOT NULL PRIMARY KEY, bootstrap_complete BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, completed_at DATETIME(3) NULL,
 maintenance_mode BOOLEAN NOT NULL DEFAULT TRUE, CHECK(id=1 AND version>=0)
) ENGINE=InnoDB;
INSERT INTO admin_authz_revision(id,revision,recovery_epoch) VALUES(1,0,UUID());
INSERT INTO admin_bootstrap(id,bootstrap_complete,version,maintenance_mode) VALUES(1,FALSE,0,TRUE);
