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
package io.qnop.service.review.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.document.DocumentAttachmentService;
import io.qnop.service.document.DocumentAttachmentService.AttachmentDownload;
import io.qnop.service.document.DocumentAttachmentService.AttachmentMetadata;
import io.qnop.service.image.ImageFixtures;
import io.qnop.service.image.PixelBudget;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The export-side pixel budget for embedded attachments (issue #907). */
class ExportAttachmentResolverTest {

  private static final UUID DOCUMENT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  private final DocumentAttachmentService attachments = mock(DocumentAttachmentService.class);
  private final ExportAttachmentResolver resolver =
      new ExportAttachmentResolver(attachments, mock(ApplicationSettingsService.class));

  @BeforeEach
  void budget() {
    when(attachments.pixelBudget()).thenReturn(new PixelBudget(16_384, 16_000_000L));
  }

  @Test
  void embedsAStoredImageWithinTheBudget() {
    String url = stored(ImageFixtures.realPng(30, 20), DocumentAttachmentService.PNG);

    Map<String, ExportImage> images = resolver.images(DOCUMENT, List.of(body(url)), ACTOR, false);

    assertThat(images).containsKey(url);
  }

  @Test
  void skipsAStoredImageDeclaringMoreThanTheBudget() {
    String png = stored(ImageFixtures.pngHeader(20_000, 20_000), DocumentAttachmentService.PNG);
    String webp = stored(ImageFixtures.webpVp8x(9_000, 9_000), DocumentAttachmentService.WEBP);

    Map<String, ExportImage> images =
        resolver.images(DOCUMENT, List.of(body(png), body(webp)), ACTOR, false);

    assertThat(images).isEmpty();
  }

  @Test
  void skipsAStoredImageWhoseHeaderCannotBeRead() {
    String url =
        stored(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2}, DocumentAttachmentService.PNG);

    assertThat(resolver.images(DOCUMENT, List.of(body(url)), ACTOR, false)).isEmpty();
  }

  private String stored(byte[] bytes, String contentType) {
    UUID id = UUID.randomUUID();
    AttachmentMetadata metadata =
        new AttachmentMetadata(id, "image", contentType, "hash", bytes.length, "key/" + id);
    when(attachments.metadata(eq(DOCUMENT), eq(id), any(), anyBoolean())).thenReturn(metadata);
    when(attachments.open(metadata))
        .thenAnswer(i -> new AttachmentDownload(new ByteArrayInputStream(bytes), bytes.length));
    return "/api/v1/documents/" + DOCUMENT + "/attachments/" + id;
  }

  private static String body(String url) {
    return "See ![shot](" + url + ")";
  }
}
