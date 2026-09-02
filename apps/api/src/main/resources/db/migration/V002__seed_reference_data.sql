-- Global reference data: the permission catalogue and the five system roles.
-- Tenant-independent, so it lives in a migration rather than tenant seeding.

INSERT INTO permissions (code, category, description) VALUES
    ('READ_SALES',      'DATA',   'Read sales data'),
    ('READ_INVENTORY',  'DATA',   'Read inventory data'),
    ('READ_CUSTOMER',   'DATA',   'Read customer records'),
    ('READ_HR',         'DATA',   'Read HR documents and employee records'),
    ('READ_SALARY',     'DATA',   'Read compensation data'),
    ('READ_POLICY',     'DATA',   'Read company policy documents'),
    ('MANAGE_RULES',    'ADMIN',  'Create and modify business rules'),
    ('MANAGE_USERS',    'ADMIN',  'Create and modify users and role assignments'),
    ('MANAGE_SOURCES',  'ADMIN',  'Connect and configure data sources'),
    ('VIEW_AUDIT',      'ADMIN',  'Read the tenant audit log'),
    ('VIEW_TRACE',      'ADMIN',  'Read full reasoning traces'),
    ('EXECUTE_ACTION',  'ACTION', 'Invoke side-effecting tools');

-- System roles are platform-level templates (tenant_id NULL).
INSERT INTO roles (name, description, is_system) VALUES
    ('SUPER_ADMIN', 'Platform operator. Cross-tenant administration.', true),
    ('ORG_ADMIN',   'Tenant administrator.',                          true),
    ('MANAGER',     'Business manager. Scoped operational data.',     true),
    ('EMPLOYEE',    'Standard employee. Policy and general data.',    true),
    ('VIEWER',      'Read-only, non-sensitive data.',                 true);

-- Role -> permission mapping.
--
-- Note what is absent: READ_SALARY is granted to NO system role. It is
-- assigned deliberately, per tenant, to an HR role. Demo query 4 ("show me
-- all employee salaries") is denied because no default role can ever satisfy
-- it -- the denial is structural, not a special case in the code.
WITH mapping(role_name, perm_code) AS (VALUES
    ('ORG_ADMIN', 'READ_SALES'),   ('ORG_ADMIN', 'READ_INVENTORY'),
    ('ORG_ADMIN', 'READ_CUSTOMER'),('ORG_ADMIN', 'READ_HR'),
    ('ORG_ADMIN', 'READ_POLICY'),  ('ORG_ADMIN', 'MANAGE_RULES'),
    ('ORG_ADMIN', 'MANAGE_USERS'), ('ORG_ADMIN', 'MANAGE_SOURCES'),
    ('ORG_ADMIN', 'VIEW_AUDIT'),   ('ORG_ADMIN', 'VIEW_TRACE'),

    ('MANAGER',   'READ_SALES'),   ('MANAGER',   'READ_INVENTORY'),
    ('MANAGER',   'READ_CUSTOMER'),('MANAGER',   'READ_POLICY'),
    ('MANAGER',   'VIEW_TRACE'),

    ('EMPLOYEE',  'READ_POLICY'),  ('EMPLOYEE',  'READ_INVENTORY'),

    ('VIEWER',    'READ_POLICY')
)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM mapping m
JOIN roles r       ON r.name = m.role_name AND r.tenant_id IS NULL
JOIN permissions p ON p.code = m.perm_code;
