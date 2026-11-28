ALTER TABLE agent_run_steps
    ADD COLUMN result_source VARCHAR(32) NOT NULL DEFAULT 'TOOL_EXECUTION',
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_started_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_agent_step_retries CHECK (retry_count BETWEEN 0 AND 2);

CREATE TABLE agent_step_recovery_attempts (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no INT NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (run_id, sequence_no, attempt_no),
    FOREIGN KEY (run_id, sequence_no) REFERENCES agent_run_steps(run_id, sequence_no),
    CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
