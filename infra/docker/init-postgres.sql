\getenv app_user GRIDIQ_DB_USER
\getenv db_name POSTGRES_DB
\set app_password `cat /run/gridiq/db/db-password`

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', :'db_name', :'app_user') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
