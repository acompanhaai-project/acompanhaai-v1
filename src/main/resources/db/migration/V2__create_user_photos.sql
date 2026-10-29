CREATE TABLE tb_user_photo (
    user_id BIGINT PRIMARY KEY REFERENCES tb_user(id) ON DELETE CASCADE,
    content BYTEA NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
