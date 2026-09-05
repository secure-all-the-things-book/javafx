# javafx

in which we secure a JavaFX-powered desktop application

The full write-up lives in [`tutorial.adoc`](tutorial.adoc) — a single Asciidoctor
document covering both parts of the video series. Every listing in it is pulled
straight out of this repository with `include::` directives, so the tutorial and the
code can't drift apart.

Render it with:

```shell
asciidoctor tutorial.adoc
```

## The modules

| module           | port  | what it is                                                       |
|------------------|-------|------------------------------------------------------------------|
| `auth`           | 9090  | Spring Authorization Server; users in Postgres, via Flyway        |
| `service`        | 8081  | an OAuth2 resource server with a single `/message` endpoint       |
| `desktop-client` | 8385  | the JavaFX app; a *public* OAuth client, plus a loopback listener |

## Running it

The desktop client discovers the authorization server's endpoints from its
`issuer-uri` at startup, so start things in this order:

```shell
docker compose up -d                    # postgres
cd auth           && ./mvnw spring-boot:run
cd service        && ./mvnw spring-boot:run
cd desktop-client && ./mvnw spring-boot:run
```

To build the native image:

```shell
cd desktop-client && ./native.sh
```
