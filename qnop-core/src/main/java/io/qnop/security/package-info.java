/**
 * Security &amp; crypto foundation shared by all auth work (issue #10, ADR-0021).
 *
 * <p>This package is framework-free of the web layer: it holds validated configuration properties
 * ({@link io.qnop.security.QnopProperties}), password hashing and symmetric text encryption beans
 * ({@link io.qnop.security.CryptoConfiguration}), HKDF-SHA256 key derivation ({@link
 * io.qnop.security.Hkdf}, {@link io.qnop.security.JwtKeyService}). The servlet {@code
 * SecurityFilterChain} lives in {@code io.qnop.web.security} (qnop-app) — see ADR-0021 for why the
 * crypto foundation and the filter chain are split across modules while sharing the layered
 * architecture (ADR-0004).
 *
 * <p>ArchUnit treats {@code io.qnop.security} as its own layer, accessible only by the service and
 * web layers.
 */
package io.qnop.security;
