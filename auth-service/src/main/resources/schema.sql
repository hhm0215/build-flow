CREATE TABLE IF NOT EXISTS admin_accounts (
    id BIGINT NOT NULL PRIMARY KEY,
    login_id VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_single_admin_id CHECK (id = 1)
);
