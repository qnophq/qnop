// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Publishing conventions (issue #497, ADR-0046): publish the two Spring-free
// PUBLISHED contracts — qnop-spi (the plugin boundary, ADR-0003) and qnop-api
// (the REST contract, ADR-0015/0021) — to GitHub Packages so the private
// qnop-enterprise repository can depend on them (ADR-0002). Applied via
// `plugins { id("qnop.publish-conventions") }` ON TOP of `qnop.java-conventions`
// (which sets group/version and already adds the sources jar).
//
// It adds: full POM metadata (AGPL-3.0-only licence, SCM, organisation), a
// javadoc jar, the GitHub Packages Maven repository, and OPT-IN PGP signing —
// signing runs only when a key is present in the environment, so a normal build
// and a SNAPSHOT publish never require GPG.

plugins {
    `java-library`
    `maven-publish`
    signing
}

java {
    // qnop.java-conventions already adds `withSourcesJar()`; a javadoc jar
    // completes the source/javadoc pair consumers expect.
    withJavadocJar()
}

// Generated modules (qnop-api-*) feed javadoc plain POJOs/interfaces; disable
// doclint so a missing @param on generated code never fails the jar, and keep
// it quiet so the build log stays readable.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // group/version come from qnop.java-conventions (the root VERSION file);
            // artifactId is the module name (e.g. qnop-spi, qnop-api-model).
            pom {
                name.set(provider { "${project.group}:${project.name}" })
                description.set(provider { project.description ?: project.name })
                url.set("https://github.com/qnophq/qnop")
                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0 only")
                        url.set("https://www.gnu.org/licenses/agpl-3.0-standalone.html")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set("devtank42 GmbH")
                    url.set("https://github.com/qnophq")
                }
                developers {
                    developer {
                        id.set("devtank42")
                        name.set("devtank42 GmbH")
                        url.set("https://github.com/qnophq")
                    }
                }
                scm {
                    url.set("https://github.com/qnophq/qnop")
                    connection.set("scm:git:https://github.com/qnophq/qnop.git")
                    developerConnection.set("scm:git:ssh://git@github.com/qnophq/qnop.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/qnophq/qnop")
            // Local publishes read `gpr.user`/`gpr.key` from ~/.gradle/gradle.properties;
            // CI falls back to the Actions-provided GITHUB_ACTOR / GITHUB_TOKEN.
            credentials {
                username =
                    providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }
        }
    }
}

// Opt-in, in-memory PGP signing: only when an ASCII-armoured key is supplied via
// SIGNING_KEY (release CI, or a local publish with the secret). Without it,
// signing is not required and no signature tasks are wired — GitHub Packages
// does not mandate signatures, so an unsigned SNAPSHOT publish stays frictionless.
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
