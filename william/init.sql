-- Main Table: feature_toggles
CREATE TABLE feature_toggles (
    id UUID PRIMARY KEY,
    feature_key VARCHAR(50) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(255),

    -- Audit Fields (Updated)
    created_by VARCHAR(255),
    create_time TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, -- Column name and type updated
    updated_by VARCHAR(255),
    update_time TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, -- Column name and type updated

    CONSTRAINT uk_feature_key UNIQUE (feature_key)
);

-- Relation Table: feature_allowed_groups
CREATE TABLE feature_allowed_groups (
    feature_toggle_id UUID NOT NULL,
    group_name VARCHAR(255) NOT NULL,

    CONSTRAINT fk_feature_groups_toggle
        FOREIGN KEY (feature_toggle_id)
        REFERENCES feature_toggles (id)
        ON DELETE CASCADE
);

-- Note on PG Update Time:
-- Unlike MySQL, PostgreSQL often requires a trigger to automatically update 'update_time'
-- on row changes if not relying solely on JPA's @LastModifiedDate handling.