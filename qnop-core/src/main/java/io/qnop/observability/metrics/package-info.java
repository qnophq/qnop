/**
 * The meters qnop records about its own work (issue #962, ADR-0065): one small class per area, each
 * owning its meter names and its tag values.
 *
 * <p>Two rules hold for everything in here. A tag value comes from a closed set, an enum or a fixed
 * catalogue, and never from user data: no ids, names, addresses or file names. And latency is
 * published as histogram buckets with fixed boundaries, so it can be aggregated across instances.
 */
package io.qnop.observability.metrics;
