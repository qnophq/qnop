# ADR-0021: OpenAPI-first REST contract tooling and the qnop-api submodule split

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)
- **Amends:** [ADR-0004](0004-layered-architecture-enforced-by-archunit.md) (module map), [ADR-0015](0015-published-rest-api-contract-module.md) (contract-first vs. code-first; purity scope)

## Context

[ADR-0015](0015-published-rest-api-contract-module.md) introduced `qnop-api` as the published REST contract but **deferred** the contract-first vs. code-first decision and left the module an empty shell. Issue #9 (the foundation for the identity epic #7) needs the tooling in place before any endpoint is written: a single source-of-truth spec, generated DTOs the frontend/SDK can consume, and generated server interfaces the controllers implement.

The hard constraint from ADR-0015/ADR-0004 is that the **published DTO surface stays Spring-free** (ArchUnit-guarded) so external consumers and a generated TS/SDK client can depend on it without pulling the server. Generated Spring MVC interfaces, by contrast, *must* reference Spring (`@RequestMapping`, `ResponseEntity`). These two outputs cannot live in one pure artifact.

The plugwerk project solved the same problem by splitting its `*-api` module into a pure `*-api-model` and a Spring `*-api-endpoint`, generating both from one spec with the openapi-generator Gradle plugin. We adopt that shape.

## Decision

- **Contract-first.** `qnop-api/src/main/resources/openapi/openapi.yaml` is the single source of truth (resolving the question ADR-0015 deferred). DTOs and server interfaces are generated from it via the `org.openapi.generator` Gradle plugin (declared `apply false` at the root like the Spring Boot plugin).
- **`qnop-api` becomes a container** holding only the spec, with two submodules generated from it:
  - **`qnop-api:qnop-api-model`** — the `java` generator in models-only mode (`globalProperties = ["models"]`). Emits plain POJOs (Jackson + Jakarta Bean Validation, `java.time` dates) into `io.qnop.api.v1.model`. **No Spring.** The `java` generator is used here (not `spring`) because the `spring` generator decorates its Java models with `org.springframework.lang.Nullable`/`@DateTimeFormat`; the `resttemplate` library is selected so the models carry no `toUrlQueryString()`/`ApiClient` coupling. (plugwerk uses `kotlin-spring` for both submodules because Kotlin's built-in nullability needs no `@Nullable`; the Java equivalent of that split is java-models + spring-apis.)
  - **`qnop-api:qnop-api-endpoint`** — the `spring` generator in apis-only mode (`globalProperties = ["apis", "supportingFiles"]`, `interfaceOnly=true`). Emits Spring MVC interfaces into `io.qnop.api.v1` that import the DTOs from `qnop-api-model`. **Depends on Spring by design.**
- **`qnop-app` implements the interfaces.** Controllers (`io.qnop.web`) implement the generated `*Api` interfaces; the DTO model arrives transitively. `qnop-core` depends on `qnop-api-model` only (the service layer must not see the Spring interfaces).
- **`/api/v1` URL versioning** is realized by a controller path prefix (`ApiPathConfig`, a `WebMvcConfigurer` applying `/api/v1` to `@RestController`s only), keeping infrastructure endpoints (actuator) unprefixed and the spec paths version-relative.
- **Purity is asserted on the model only.** ArchUnit's `restApiModelStaysPure` checks `io.qnop.api.v1.model..` (formerly all of `io.qnop.api..`); the endpoint package is intentionally Spring-coupled.
- **Spotless excludes generated sources** (`**/build/generated/**`): generated code is neither hand-maintained nor SPDX-headered.

## Consequences

- One versioned source of truth for the FE/BE/external contract; a TS client and an SDK can be generated from the same spec.
- The published DTO artifact (`qnop-api-model`) stays a clean, Spring-free stability surface; the Spring coupling is isolated in `qnop-api-endpoint`.
- The module count grows (the `:qnop-api` container + two submodules); ADR-0004's "four modules" wording is amended accordingly.
- A generator/Spring-Boot-version coupling exists: the `spring` generator runs with `useSpringBoot3=true` (jakarta namespace, which Spring Boot 4 uses) and `interfaceOnly` to keep the generated surface minimal. If a future Spring Boot bump outpaces the generator templates, the fallback is code-first interfaces.

## Alternatives considered

- **DTOs + Spring interfaces both in one `qnop-api`** (relax purity, drop the ArchUnit rule) — rejected: contaminates the published consumer artifact with Spring, defeating ADR-0015's reason to exist.
- **`spring` generator for the model too** (one generator, like plugwerk) — rejected for Java: its Java models carry Spring annotations, breaking purity. The split-generator approach is the Java-idiomatic equivalent.
- **Code-first (hand-written DTOs + springdoc to emit the spec)** — rejected as the default: a generated, versioned spec-as-contract is the ADR-0015 intent; kept only as the fallback if generator/Boot drift appears.
