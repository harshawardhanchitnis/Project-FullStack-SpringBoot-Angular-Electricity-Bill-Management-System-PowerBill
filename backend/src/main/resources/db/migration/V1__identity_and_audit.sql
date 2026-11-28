CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash text NOT NULL,
    full_name varchar(120) NOT NULL,
    mobile_number varchar(10) NOT NULL CHECK (mobile_number ~ '^[6-9][0-9]{9}$'),
    role text NOT NULL CHECK (role IN ('CUSTOMER','BILLING_OFFICER','SUPPORT_AGENT','FIELD_ENGINEER','SUPER_ADMIN')),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    email_verified boolean NOT NULL DEFAULT false,
    failed_login_attempts integer NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until timestamptz,
    session_version integer NOT NULL DEFAULT 0,
    theme_preference text NOT NULL DEFAULT 'SYSTEM' CHECK (theme_preference IN ('SYSTEM','LIGHT','DARK')),
    budget_alerts boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash char(64) NOT NULL UNIQUE,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by_id uuid REFERENCES refresh_tokens(id),
    user_agent varchar(255),
    ip_address inet,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id, expires_at);

CREATE TABLE password_reset_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (user_id);

CREATE TABLE email_verification_tokens (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);

CREATE TABLE audit_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    actor_user_id uuid,
    actor_email varchar(254),
    actor_role text,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid,
    correlation_id varchar(64),
    ip_address inet,
    before_state jsonb,
    after_state jsonb,
    created_at timestamptz NOT NULL
);
CREATE INDEX audit_logs_created_idx ON audit_logs (created_at DESC, id DESC);
CREATE INDEX audit_logs_entity_idx ON audit_logs (entity_type, entity_id);
CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_user_id);

CREATE FUNCTION prevent_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are append-only';
END;
$$;

CREATE TRIGGER audit_logs_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
ON audit_logs FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();

CREATE TABLE event_publication (
    id uuid PRIMARY KEY,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamptz NOT NULL,
    completion_date timestamptz,
    status text,
    completion_attempts integer,
    last_resubmission_date timestamptz
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
