# fgv-membership-server

The API of Medlemskap, FGV's application for memberships and membership fees. The product spec is in
[`fgv-membership-spec`](../fgv-membership-spec/docs/product-spec.md).

Spring Boot 4, Java 25, MySQL with Flyway, Spring for GraphQL, Spring Security as an OAuth2 resource server for FGV's
Keycloak.

## What is here

| Package        | What it does                                                                                              |
|----------------|-----------------------------------------------------------------------------------------------------------|
| `registration` | `POST /api/registration` (Turnstile, rate limits, creates the user in Keycloak, sends the verification link) and `POST /api/registration/verify`. Removes accounts never verified |
| `access`       | Which applications a user may use and with which roles; `RoleLadder` makes `ADMIN` include `EDITOR` and `USER`; the audit of changes |
| `admin`        | GraphQL for administrators (`ADMIN`): list, find, activate, deactivate, reject, edit, set access and roles |
| `keycloak`     | Keycloak's admin REST API, with `fgv-membership-service`                                                  |
| `mail`         | The emails Keycloak's `fgv-admin-notification` used to send, and the payment mails                        |
| `membership`   | Memberships per spexare and year, and the feed Spexregister pulls (`SYNC`)                                 |
| `payment`      | Fees, payment requests and payments (Swish, bankgiro, bank transfer), reminders, revival requests         |
| `bank`         | Import of bank statements, matching of transactions to payment requests (`TREASURER`)                       |
| `board`        | Members, renewals, personal payment links (`/api/personal`, public: the token is the key), Excel exports  |
| `application`  | Applications for a membership, decided by the board                                                        |
| `external`     | What other applications call: `/api/external/payments` and `/api/external/lookup` (`PAYMENT`), default access |
| `maintenance`  | The scheduled jobs, which an administrator can also run on demand                                          |

The API guide (REST Docs and the GraphQL guide) is served from `/docs` and built from `src/main/asciidoc`.

## Running

```shell
./gradlew test                # unit tests
./gradlew integrationTest     # needs Docker: MySQL and Keycloak in Testcontainers
./gradlew check               # both, with coverage
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun   # needs MySQL and Keycloak, see Local stack
```

## Local stack

Like the other FGV servers, this one has a `docker-compose.yaml` that runs next to `fgv-infra-deploy`'s local stack
(Keycloak, Mailpit and the proxy on the Docker network `fgv-local`):

```shell
scripts/init-secrets.sh          # random database passwords in .docker/secrets (the placeholders are CHANGE_ME)
scripts/configure-keycloak.sh    # copies the secret of fgv-membership-service from the local Keycloak, and tells
                                 # the web's .env.local about the fgv-membership client
docker compose up -d             # MySQL and the API image; the API is reached at https://api.membership.fgv.local.nu:8443
```

To run the API from the IDE instead, publish MySQL's port by uncommenting `ports` in `docker-compose.yaml`, start only
`membership-mysql`, and run the `local` profile (port 8090, management port 9010) with
`SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3309/membership` and the user and password of `.docker/secrets`. The
proxy sends `api.membership.fgv.local.nu` to the container, or to port 8090 on the host when the container is not up.
The secret files are committed as placeholders (`git update-index --skip-worktree` is done by the scripts), and
`.docker` is otherwise ignored, so a new secret file is added with `git add -f`.

## Sample data

`API_IMPORT_SAMPLE_DATA=true` (in `.env`, or `membership.data.import-sample-data`) replaces everything with sample data at
every start, as in the other FGV servers; it is refused in the `production` profile. It needs, running:

- fgv-infra-deploy's sample users (`bin/seed-users.sh --local`): `admin@fgv.nu`, `editor@fgv.nu`, `user@fgv.nu`,
  `treasurer@fgv.nu` and `no-access@fgv.nu` are used by their fixed ids, and their accounts are never changed;
- Spexregister with its sample data: the spexare are the ones linked to `member01@fgv.nu` to `member45@fgv.nu`.

It creates fee levels, memberships over several years in every status (also life, support, family and a revival's), requests
that are open, reported, paid by hand or by bank transfer, personal links (`sample-personal-link-open` and
`sample-personal-link-reported` are the tokens), applications and an audit trail. The one thing it does in Keycloak is to
create two accounts waiting for approval (`pending.verified@fgv.nu`, `pending.unverified@fgv.nu`) when missing.
Two sample bank statements, one per bank format and with references of requests that are still open, are written to
`API_SAMPLE_STATEMENT_DIR` (`.docker/sample-data` with Docker Compose, otherwise `./sample-data`), to upload as the treasurer.

## Configuration

| Variable                           | Purpose                                                                              |
|------------------------------------|--------------------------------------------------------------------------------------|
| `API_KEYCLOAK_URL`, `API_KEYCLOAK_REALM` | Keycloak and the realm                                                         |
| `API_KEYCLOAK_SERVICE_CLIENT_SECRET` | The secret of `fgv-membership-service`                                             |
| `API_TURNSTILE_SECRET_KEY`         | Turnstile's secret key. **Without it registration is refused**; the `local` profile skips the check. Cloudflare's test secret `1x0000000000000000000000000000000AA` always passes, `2x0000000000000000000000000000000AA` always fails |
| `API_REGISTRATION_NOTIFY_RECIPIENTS` | Comma-separated emails told when a new user has verified their address             |
| `API_SECURITY_AUTHORIZED_PARTIES`  | Clients (`azp`) whose access tokens are accepted: the web and the applications that call the API |
| `API_PAYMENT_*`, `API_SPEXREGISTER_GRAPHQL_URL`, `API_SYNC_NOTIFY_URLS`, `API_DEFAULT_ACCESS_EXCLUDED` | Where members pay, and the neighbouring applications |
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
