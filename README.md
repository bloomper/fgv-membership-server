# fgv-membership-server

The API of Medlemskap, FGV's application for memberships and membership fees. The product spec is in
[`fgv-membership-spec`](../fgv-membership-spec/docs/product-spec.md).

Spring Boot 4, Java 25, MySQL with Flyway, Spring for GraphQL, Spring Security as an OAuth2 resource server for FGV's
Keycloak.

## What is here

Only the accounts part of the spec (sections 5.11 and 5.12) so far:

| Package        | What it does                                                                                              |
|----------------|-----------------------------------------------------------------------------------------------------------|
| `registration` | `POST /api/registration` (Turnstile, rate limits, creates the user in Keycloak, sends the verification link) and `POST /api/registration/verify`. Removes accounts never verified |
| `access`       | Which applications a user may use and with which roles; `RoleLadder` makes `ADMIN` include `EDITOR` and `USER`; the audit of changes |
| `admin`        | GraphQL for administrators (`ADMIN`): list, find, activate, deactivate, reject, edit, set access and roles |
| `keycloak`     | Keycloak's admin REST API, with `fgv-membership-service`                                                  |
| `mail`         | The emails Keycloak's `fgv-admin-notification` used to send: approval, first access, rejection, new user |

Memberships, payments and the rest of the spec follow in later milestones.

## Running

```shell
./gradlew test
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun   # needs MySQL and Keycloak, see fgv-membership-deploy
```

## Configuration

| Variable                           | Purpose                                                                              |
|------------------------------------|--------------------------------------------------------------------------------------|
| `API_KEYCLOAK_URL`, `API_KEYCLOAK_REALM` | Keycloak and the realm                                                         |
| `API_KEYCLOAK_SERVICE_CLIENT_SECRET` | The secret of `fgv-membership-service`                                             |
| `API_TURNSTILE_SECRET_KEY`         | Turnstile's secret key. **Without it registration is refused**; the `local` profile skips the check. Cloudflare's test secret `1x0000000000000000000000000000000AA` always passes, `2x0000000000000000000000000000000AA` always fails |
| `API_REGISTRATION_NOTIFY_RECIPIENTS` | Comma-separated emails told when a new user has verified their address             |
| `API_WEB_URL`                      | Medlemskap Web, used in links in emails                                              |
| `SMTP_*`, `API_MAIL_FROM`          | The mail relay                                                                       |
| `MYSQL_API_USER_PASSWORD`          | Database password; also read from `/run/secrets`                                     |

The applications and their ladders of roles are `membership.applications` in `application.yml`. Granting a role grants
every role before it; revoking a role revokes every role after it. Service roles are not listed, so they cannot be
given to people.

## Keycloak

`fgv-membership-service` needs the `realm-management` roles `manage-users`, `view-users`, `query-users`,
`query-groups`, `view-clients` and `view-realm`, which `fgv-keycloak`'s realm gives it. Keycloak keeps passwords and
sessions; Medlemskap sets a user's password only by asking Keycloak to email an *update password* link.

## License

Apache 2.0.
