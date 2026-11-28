CREATE TABLE agent_runs (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_message VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_json JSON NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    heartbeat_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    UNIQUE KEY uk_agent_request (user_id, client_request_id),
    KEY idx_agent_conversation (user_id, conversation_id, created_at),
    CONSTRAINT chk_agent_run_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_run_steps (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no INT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments_json MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(64) NULL,
    result_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (run_id, sequence_no),
    CONSTRAINT fk_agent_step_run FOREIGN KEY (run_id) REFERENCES agent_runs(run_id),
    CONSTRAINT chk_agent_step_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
