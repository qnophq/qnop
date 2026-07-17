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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The enterprise schema seam (issue #254, ADR-0039): the master changelog ends with an {@code
 * includeAll} of {@code classpath*:db/changelog/enterprise/}, so a separately-licensed module
 * (ADR-0002) can contribute schema without touching the community changelog. The test classpath
 * carries a probe changelog standing in for an enterprise JAR; this IT proves the probe is applied,
 * namespaced as agreed, and ordered after every community migration. The no-op half of the contract
 * — no enterprise changelogs, no effect — is covered by the shipped artifact containing none and
 * every other IT booting through the same master changelog.
 */
class EnterpriseLiquibaseSeamIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("an enterprise changelog on the classpath is applied through the seam")
  void probeChangesetApplied() {
    Map<String, Object> probe =
        jdbc.queryForMap(
            "SELECT author, orderexecuted FROM databasechangelog WHERE id = 'e0001-seam-probe'");
    assertThat(probe.get("author")).isEqualTo("qnop-enterprise");

    // The probe table exists and is usable.
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM enterprise_seam_probe", Integer.class);
    assertThat(rows).isZero();

    // Enterprise changesets run AFTER every community changeset (community authors it as 'qnop').
    Integer lastCommunity =
        jdbc.queryForObject(
            "SELECT max(orderexecuted) FROM databasechangelog WHERE author = 'qnop'",
            Integer.class);
    assertThat((Integer) probe.get("orderexecuted")).isGreaterThan(lastCommunity);
  }
}
