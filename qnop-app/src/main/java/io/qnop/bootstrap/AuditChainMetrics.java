/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.qnop.bootstrap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.qnop.service.audit.AuditChainVerificationService;
import io.qnop.service.audit.AuditChainVerifier;
import org.springframework.stereotype.Component;

/**
 * Publishes the outcome of the latest audit-chain verification (issue #928, ADR-0043): {@code
 * qnop.audit.chain.intact} is 1 for an intact chain and 0 for a broken one or a run that failed —
 * this instance's own last run, else the outcome the scheduler persisted for the job, so a restart
 * or an instance that did not run it still reports; NaN only before any run is known. {@code
 * qnop.audit.chain.problems} counts the breaks found by this instance's last run. Alert on {@code
 * intact == 0}.
 */
@Component
class AuditChainMetrics implements MeterBinder {

  private final AuditChainVerificationService verification;

  AuditChainMetrics(AuditChainVerificationService verification) {
    this.verification = verification;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(
            "qnop.audit.chain.intact",
            verification,
            v -> v.lastKnownIntact().map(intact -> intact ? 1.0 : 0.0).orElse(Double.NaN))
        .description("1 if the last known audit-chain verification passed, else 0")
        .register(registry);
    Gauge.builder(
            "qnop.audit.chain.problems",
            verification,
            v ->
                v.lastReport()
                    .map(AuditChainVerifier.Report::problemCount)
                    .map(Integer::doubleValue)
                    .orElse(Double.NaN))
        .description("Breaks found by the last audit-chain verification on this instance")
        .register(registry);
  }
}
