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
package io.qnop.service.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.repository.UserRepository;
import io.qnop.service.avatar.AvatarStorage.NewAvatar;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AvatarServiceTest {

  private AvatarStorage storage;
  private UserRepository users;
  private AvatarService service;

  private final UUID userId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    storage = mock(AvatarStorage.class);
    users = mock(UserRepository.class);
    service = new AvatarService(storage, users);
    when(users.existsById(userId)).thenReturn(true);
    when(storage.findUpdatedAt(userId))
        .thenReturn(Optional.of(Instant.parse("2026-06-26T00:00:00Z")));
  }

  @Test
  void storeRejectsUnknownUser() {
    when(users.existsById(userId)).thenReturn(false);
    assertThatThrownBy(() -> service.store(userId, png(64, 64), actor))
        .isInstanceOf(AvatarValidationException.class)
        .satisfies(e -> assertThat(((AvatarValidationException) e).getStatus()).isEqualTo(404));
    verify(storage, never()).put(any());
  }

  @Test
  void storeRejectsEmptyUpload() {
    assertThatThrownBy(() -> service.store(userId, new byte[0], actor))
        .isInstanceOf(AvatarValidationException.class);
    verify(storage, never()).put(any());
  }

  @Test
  void storeRejectsOversizedPayload() {
    byte[] tooBig = new byte[(int) AvatarLimits.MAX_SIZE_BYTES + 1];
    assertThatThrownBy(() -> service.store(userId, tooBig, actor))
        .isInstanceOf(AvatarValidationException.class)
        .satisfies(e -> assertThat(((AvatarValidationException) e).getStatus()).isEqualTo(413));
    verify(storage, never()).put(any());
  }

  @Test
  void storeRejectsUnsupportedType() {
    assertThatThrownBy(() -> service.store(userId, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, actor))
        .isInstanceOf(AvatarValidationException.class)
        .satisfies(e -> assertThat(((AvatarValidationException) e).getStatus()).isEqualTo(415));
    verify(storage, never()).put(any());
  }

  @Test
  void storeRejectsUnreadablePng() {
    // Valid PNG signature but no decodable image data → ImageIO returns null.
    byte[] brokenPng = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
    assertThatThrownBy(() -> service.store(userId, brokenPng, actor))
        .isInstanceOf(AvatarValidationException.class)
        .satisfies(e -> assertThat(((AvatarValidationException) e).getStatus()).isEqualTo(400));
    verify(storage, never()).put(any());
  }

  @Test
  void storeRejectsOversizedDimensions() throws IOException {
    int tooWide = AvatarLimits.MAX_DIMENSION_PX + 1;
    assertThatThrownBy(() -> service.store(userId, png(tooWide, 16), actor))
        .isInstanceOf(AvatarValidationException.class)
        .satisfies(e -> assertThat(((AvatarValidationException) e).getStatus()).isEqualTo(400));
    verify(storage, never()).put(any());
  }

  @Test
  void storeAcceptsPngAndPersistsSniffedTypeWithDimensions() throws IOException {
    Instant updatedAt = service.store(userId, png(48, 48), actor);

    ArgumentCaptor<NewAvatar> captor = ArgumentCaptor.forClass(NewAvatar.class);
    verify(storage).put(captor.capture());
    NewAvatar stored = captor.getValue();
    assertThat(stored.userId()).isEqualTo(userId);
    assertThat(stored.contentType()).isEqualTo(AvatarLimits.PNG);
    assertThat(stored.width()).isEqualTo(48);
    assertThat(stored.height()).isEqualTo(48);
    assertThat(stored.sha256()).hasSize(64);
    assertThat(stored.updatedBy()).isEqualTo(actor);
    assertThat(updatedAt).isEqualTo(Instant.parse("2026-06-26T00:00:00Z"));
  }

  @Test
  void storeAcceptsJpeg() throws IOException {
    service.store(userId, jpeg(32, 32), actor);
    ArgumentCaptor<NewAvatar> captor = ArgumentCaptor.forClass(NewAvatar.class);
    verify(storage).put(captor.capture());
    assertThat(captor.getValue().contentType()).isEqualTo(AvatarLimits.JPEG);
  }

  @Test
  void storeAcceptsWebpWithoutReadableDimensions() {
    // A RIFF/WEBP header is enough to sniff; ImageIO has no WebP plugin, so dimensions stay null
    // and only the size cap applies.
    byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    service.store(userId, webp, actor);
    ArgumentCaptor<NewAvatar> captor = ArgumentCaptor.forClass(NewAvatar.class);
    verify(storage).put(captor.capture());
    assertThat(captor.getValue().contentType()).isEqualTo(AvatarLimits.WEBP);
    assertThat(captor.getValue().width()).isNull();
  }

  @Test
  void getRemoveAndUpdatedAtDelegateToStorage() {
    service.remove(userId);
    verify(storage).remove(userId);
    service.get(userId);
    verify(storage).find(userId);
    service.updatedAt(userId);
    // findUpdatedAt(userId) is also stubbed in setUp; verify the single-id delegation happened.
    verify(storage).findUpdatedAt(userId);
  }

  private static byte[] png(int w, int h) throws IOException {
    return raster(w, h, BufferedImage.TYPE_INT_ARGB, "png");
  }

  private static byte[] jpeg(int w, int h) throws IOException {
    return raster(w, h, BufferedImage.TYPE_INT_RGB, "jpg");
  }

  private static byte[] raster(int w, int h, int type, String format) throws IOException {
    BufferedImage image = new BufferedImage(w, h, type);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }
}
