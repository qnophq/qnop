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
package io.qnop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PublicBaseUrlTest {

  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
  private final PublicBaseUrl baseUrl = new PublicBaseUrl(settings);

  private final Logger logger = (Logger) LoggerFactory.getLogger(PublicBaseUrl.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  void captureLog() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    events.start();
    logger.addAppender(events);
  }

  @AfterEach
  void releaseLog() {
    logger.detachAppender(events);
    logger.setLevel(previousLevel);
  }

  @Test
  void aConfiguredBaseUrlIsTrimmedAndLogsNothing() {
    when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL))
        .thenReturn(" https://qnop.example// ");

    assertThat(baseUrl.configured()).contains("https://qnop.example");
    assertThat(baseUrl.orReportMissing("links break")).isEqualTo("https://qnop.example");
    assertThat(events.list).isEmpty();
  }

  @Test
  void aMissingBaseUrlWarnsOnceAndThenOnlyAtDebug() {
    when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL)).thenReturn("");

    assertThat(baseUrl.configured()).isEmpty();
    for (int i = 0; i < 5; i++) {
      assertThat(baseUrl.orReportMissing("notification mail links will be relative")).isEmpty();
    }
    baseUrl.reportMissing("export attachments will not be linked");

    assertThat(levels())
        .containsExactly(
            Level.WARN, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG);
    assertThat(events.list.getFirst().getFormattedMessage())
        .contains("general.base_url is not configured")
        .contains("notification mail links will be relative")
        .contains("Settings → General → Base URL");
  }

  @Test
  void anUnsetBaseUrlIsMissingWhetherNullOrBlank() {
    when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL)).thenReturn(null, "   ");

    assertThat(baseUrl.configured()).isEmpty();
    assertThat(baseUrl.configured()).isEmpty();
  }

  @Test
  void changingTheBaseUrlReArmsTheWarning() {
    when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL)).thenReturn("");
    baseUrl.reportMissing("the digest will have no links");
    baseUrl.reportMissing("the digest will have no links");

    baseUrl.onSettingsChanged(Set.of(ApplicationSettingKey.GENERAL_APPLICATION_NAME));
    baseUrl.reportMissing("the digest will have no links");
    baseUrl.onSettingsChanged(Set.of(ApplicationSettingKey.GENERAL_BASE_URL));
    baseUrl.reportMissing("the digest will have no links");

    assertThat(levels()).containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN);
  }

  private List<Level> levels() {
    return events.list.stream().map(ILoggingEvent::getLevel).toList();
  }
}
