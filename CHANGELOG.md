# Changelog

## [2026-08-20] Hotfixes

### Fixed
- **Platform Organization Creation**: Fixed a 500 Internal Server Error when creating a new organization from the platform admin dashboard.
  - **Reason**: The \PlatformOrganizationController\ attempts to log a subscription audit event in the same request as creating the organization. The \AuditServiceImpl\ was using \@Transactional(propagation = Propagation.REQUIRES_NEW)\. This caused the audit logger to open an entirely new, isolated database transaction which could not see the uncommitted organization row created by the controller, resulting in a foreign key constraint violation. Removed the \REQUIRES_NEW\ propagation so the audit log shares the transaction and successfully commits alongside the new organization.
- **Flyway Database Migrations**: Fixed a crash on startup caused by two \V097\ migration files conflicting. Renamed the call log audit migration to \V150\ to avoid conflicts.
- **Database PgVector Extension**: Fixed a crash at \V032\ where the custom Postgres Dockerfile lacked the \pgvector\ extension. Added \postgresql-16-pgvector\ to the \postgres.Dockerfile\.

