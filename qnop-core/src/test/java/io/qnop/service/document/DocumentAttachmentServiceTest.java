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
package io.qnop.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.DocumentAttachment;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.image.ImageFixtures;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Upload-time image checks for discussion attachments (issue #907). */
class DocumentAttachmentServiceTest {

  private static final UUID DOCUMENT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  private final DocumentAccessService access = mock(DocumentAccessService.class);
  private final DocumentAttachmentRepository repository = mock(DocumentAttachmentRepository.class);
  private final StorageService storage = mock(StorageService.class);
  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);

  private DocumentAttachmentService service;

  @BeforeEach
  void setUp() {
    when(settings.getInteger(ApplicationSettingKey.UPLOAD_ATTACHMENT_MAX_FILE_SIZE_MB))
        .thenReturn(10);
    when(storage.stage(any(InputStream.class), anyString(), anyLong()))
        .thenAnswer(
            invocation -> {
              byte[] bytes = invocation.<InputStream>getArgument(0).readAllBytes();
              return new StagedObject("staging/key", "hash", bytes.length);
            });
    when(repository.save(any(DocumentAttachment.class))).thenAnswer(i -> i.getArgument(0));
    service = new DocumentAttachmentService(access, repository, storage, settings, 16);
  }

  @Test
  void acceptsAnImageWithinThePixelBudget() {
    DocumentAttachmentService.UploadedAttachment uploaded =
        service.store(DOCUMENT, ACTOR, false, upload(ImageFixtures.realPng(64, 32)), "a.png");

    assertThat(uploaded.contentType()).isEqualTo(DocumentAttachmentService.PNG);
  }

  @Test
  void storesAnImageDeclaringMoreThanThePixelBudgetAsDownloadOnly() {
    for (byte[] declared :
        new byte[][] {
          ImageFixtures.pngHeader(20_000, 20_000),
          ImageFixtures.pngHeader(32_769, 1),
          ImageFixtures.webpVp8x(5_000, 5_000),
          ImageFixtures.gif(10_000, 10_000, new int[] {0, 0, 1, 1})
        }) {
      DocumentAttachmentService.UploadedAttachment uploaded =
          service.store(DOCUMENT, ACTOR, false, upload(declared), "x.png");

      assertThat(uploaded.contentType()).isEqualTo(DocumentAttachmentService.BINARY);
    }
  }

  @Test
  void storesAnImageWhoseHeaderCannotBeReadAsDownloadOnly() {
    byte[] signatureOnly = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x42, 0x42};

    assertThat(service.store(DOCUMENT, ACTOR, false, upload(signatureOnly), "x.png").contentType())
        .isEqualTo(DocumentAttachmentService.BINARY);
  }

  @Test
  void theBudgetIsConfigurableInMegapixels() {
    assertThat(service.pixelBudget().maxPixels()).isEqualTo(16_000_000L);
    assertThat(
            new DocumentAttachmentService(access, repository, storage, settings, 0)
                .pixelBudget()
                .maxPixels())
        .isEqualTo(DocumentAttachmentService.DEFAULT_MAX_IMAGE_MEGAPIXELS * 1_000_000L);
  }

  @Test
  void nonImagesAreNotProbed() {
    DocumentAttachmentService.UploadedAttachment uploaded =
        service.store(DOCUMENT, ACTOR, false, upload("%PDF-1.4 body".getBytes()), "notes.pdf");

    assertThat(uploaded.contentType()).isEqualTo(DocumentAttachmentService.PDF);
  }

  private static UploadSource upload(byte[] bytes) {
    return new UploadSource() {
      @Override
      public InputStream open() {
        return new ByteArrayInputStream(bytes);
      }

      @Override
      public long declaredSize() {
        return bytes.length;
      }
    };
  }
}
