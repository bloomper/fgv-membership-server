-- A support member pays in Medlemskap too, so a support membership gives Medlemskap's lowest role by default. Databases
-- that wrote down the support defaults before this get it added; an administrator can still take it away.
INSERT INTO default_access (profile, client_id, role)
SELECT 'SUPPORT', 'fgv-membership', 'USER' FROM DUAL
WHERE EXISTS (SELECT 1 FROM app_setting WHERE name = 'default-access.seeded.support')
  AND NOT EXISTS (SELECT 1 FROM default_access WHERE profile = 'SUPPORT' AND client_id = 'fgv-membership');
