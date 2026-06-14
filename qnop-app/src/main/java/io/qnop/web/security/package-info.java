/**
 * Servlet security wiring for the qnop Community server (issue #10, ADR-0021).
 *
 * <p>Holds the {@code SecurityFilterChain}: stateless session policy, CORS, security headers/CSP, a
 * JSON 401 entry point, and the public/authenticated request matrix. The framework-light crypto
 * foundation (password hashing, text encryption, key derivation, validated properties) lives apart
 * in {@code io.qnop.security} (qnop-core). As part of the web layer this package may depend on the
 * service and security layers but is itself accessed by nobody (ADR-0004).
 */
package io.qnop.web.security;
