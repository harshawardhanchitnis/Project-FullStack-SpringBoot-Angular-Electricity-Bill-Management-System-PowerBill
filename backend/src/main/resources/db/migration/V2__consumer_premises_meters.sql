CREATE TABLE consumer_accounts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES users(id),
    consumer_number varchar(24) NOT NULL UNIQUE,
    full_name varchar(120) NOT NULL,
    account_type text NOT NULL CHECK (account_type IN ('RESIDENTIAL','COMMERCIAL','INDUSTRIAL')),
    billing_email varchar(254) NOT NULL,
    mobile_number varchar(10) NOT NULL CHECK (mobile_number ~ '^[6-9][0-9]{9}$'),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','DISCONNECTED')),
    auto_pay_enabled boolean NOT NULL DEFAULT false,
    monthly_budget_inr numeric(12,2) CHECK (monthly_budget_inr >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE service_premises (
    id uuid PRIMARY KEY,
    consumer_account_id uuid NOT NULL REFERENCES consumer_accounts(id),
    label varchar(100) NOT NULL,
    address_line varchar(255) NOT NULL,
    city varchar(100) NOT NULL,
    state varchar(100) NOT NULL,
    pincode varchar(6) NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    region_zone varchar(100) NOT NULL,
    latitude numeric(8,6) CHECK (latitude BETWEEN -90 AND 90),
    longitude numeric(9,6) CHECK (longitude BETWEEN -180 AND 180),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX service_premises_account_idx ON service_premises (consumer_account_id);

CREATE TABLE smart_meters (
    id uuid PRIMARY KEY,
    premise_id uuid NOT NULL REFERENCES service_premises(id),
    meter_serial varchar(40) NOT NULL UNIQUE,
    connection_type text NOT NULL CHECK (connection_type IN ('DOMESTIC','COMMERCIAL','INDUSTRIAL')),
    sanctioned_load_kw numeric(12,4) NOT NULL CHECK (sanctioned_load_kw > 0),
    solar_net_metering boolean NOT NULL DEFAULT false,
    solar_capacity_kw numeric(12,4),
    tariff_plan_id uuid,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FAULTY','DISCONNECTED')),
    installed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (
        (solar_net_metering AND solar_capacity_kw IS NOT NULL AND solar_capacity_kw > 0)
        OR (NOT solar_net_metering AND solar_capacity_kw IS NULL)
    )
);
CREATE INDEX smart_meters_premise_idx ON smart_meters (premise_id);
