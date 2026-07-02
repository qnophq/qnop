/**
 * Object-storage extension point (ADR-0005). A {@link io.qnop.spi.storage.StorageProvider} stores,
 * retrieves and deletes binary content by opaque key; the Community default is an S3/MinIO adapter
 * in {@code io.qnop.service.storage}, but an add-on may supply its own. Pure contract: no Spring,
 * no persistence, no internal-module types — only the JDK.
 */
package io.qnop.spi.storage;
