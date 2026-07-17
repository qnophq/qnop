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
package io.qnop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Comment attachment API acceptance (issue #446). Owner {@code MEMBER_ID}; reviewer {@code
 * AUDITOR_ID}; {@code EXTERNAL_ID} is a non-participant and must see 404 in both directions
 * (anti-enumeration). The stored type comes from magic-byte sniffing — the declared MIME never
 * decides: raster images serve inline, PDF keeps its type, everything else collapses to
 * octet-stream, and non-images always download (attachment disposition).
 */
class DocumentAttachmentApiIT extends SeededIntegrationTest {

  /** A minimal but honest PNG header followed by payload bytes. */
  private static final byte[] PNG_BYTES = pngBytes(64);

  @Autowired private DocumentRepository documents;
  @Autowired private ReviewParticipantRepository participants;

  private static byte[] pngBytes(int size) {
    byte[] bytes = new byte[size];
    byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, bytes, 0, signature.length);
    Arrays.fill(bytes, signature.length, size, (byte) 0x42);
    return bytes;
  }

  private UUID seedDocument() {
    Document document = documents.save(new Document(MEMBER_ID, "Master services agreement"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    return document.getId();
  }

  private <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private MockMultipartHttpServletRequestBuilder uploadRequest(
      UUID documentId, byte[] bytes, String fileName) {
    return multipart("/api/v1/documents/" + documentId + "/attachments")
        .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes));
  }

  @Test
  void participantUploadsAnImageAndAnotherParticipantFetchesIt() throws Exception {
    UUID documentId = seedDocument();

    String json =
        mockMvc
            .perform(as(uploadRequest(documentId, PNG_BYTES, "screenshot.png"), AUDITOR_ID))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.fileName").value("screenshot.png"))
            .andExpect(jsonPath("$.url").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = JsonPath.read(json, "$.url");
    assertThat(url).startsWith("/api/v1/documents/" + documentId + "/attachments/");

    MvcResult fetched =
        mockMvc
            .perform(as(get(url), MEMBER_ID))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", startsWith("inline")))
            .andExpect(header().exists("ETag"))
            .andReturn();
    assertThat(fetched.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

    // Immutable content: a matching If-None-Match short-circuits to 304.
    String etag = fetched.getResponse().getHeader("ETag");
    mockMvc
        .perform(as(get(url), MEMBER_ID).header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void sniffsAPdfAndServesItAsADownload() throws Exception {
    UUID documentId = seedDocument();

    byte[] pdf = "%PDF-1.7 fake but honest header".getBytes();
    String json =
        mockMvc
            .perform(as(uploadRequest(documentId, pdf, "report.pdf"), AUDITOR_ID))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contentType").value("application/pdf"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = JsonPath.read(json, "$.url");

    mockMvc
        .perform(as(get(url), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/pdf"))
        // Non-images never render inline in the app origin.
        .andExpect(header().string("Content-Disposition", startsWith("attachment")));
  }

  @Test
  void collapsesUnknownBytesToOctetStreamRegardlessOfTheDeclaredName() throws Exception {
    UUID documentId = seedDocument();

    // Named .png, but the bytes are no image — the declared name/MIME never decides.
    byte[] text = "just some text pretending".getBytes();
    String json =
        mockMvc
            .perform(as(uploadRequest(documentId, text, "fake.png"), AUDITOR_ID))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contentType").value("application/octet-stream"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = JsonPath.read(json, "$.url");

    mockMvc
        .perform(as(get(url), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/octet-stream"))
        .andExpect(header().string("Content-Disposition", startsWith("attachment")));
  }

  @Test
  void rejectsAnOversizedImageWith413() throws Exception {
    UUID documentId = seedDocument();

    byte[] tooBig = pngBytes(10 * 1024 * 1024 + 1);
    mockMvc
        .perform(as(uploadRequest(documentId, tooBig, "huge.png"), AUDITOR_ID))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void theCapIsAdminConfigurableWithoutARestart() throws Exception {
    UUID documentId = seedDocument();
    byte[] twoMb = pngBytes(2 * 1024 * 1024);

    // 2 MB passes under the 10 MB default…
    mockMvc
        .perform(as(uploadRequest(documentId, twoMb, "ok.png"), AUDITOR_ID))
        .andExpect(status().isCreated());

    // …then the admin tightens upload.attachment_max_file_size_mb to 1 MB (issue #446).
    updateAttachmentCap("1");
    try {
      mockMvc
          .perform(as(uploadRequest(documentId, twoMb, "now-too-big.png"), AUDITOR_ID))
          .andExpect(status().isPayloadTooLarge());
    } finally {
      updateAttachmentCap("10"); // restore the default for the shared context
    }

    mockMvc
        .perform(as(uploadRequest(documentId, twoMb, "ok-again.png"), AUDITOR_ID))
        .andExpect(status().isCreated());
  }

  private void updateAttachmentCap(String megabytes) throws Exception {
    mockMvc
        .perform(
            as(patch("/api/v1/admin/settings"), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"values\":{\"upload.attachment_max_file_size_mb\":\"" + megabytes + "\"}}"))
        .andExpect(status().isOk());
  }

  @Test
  void nonParticipantSees404InBothDirections() throws Exception {
    UUID documentId = seedDocument();

    mockMvc
        .perform(as(uploadRequest(documentId, PNG_BYTES, "s.png"), EXTERNAL_ID))
        .andExpect(status().isNotFound());

    String json =
        mockMvc
            .perform(as(uploadRequest(documentId, PNG_BYTES, "s.png"), AUDITOR_ID))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = JsonPath.read(json, "$.url");

    mockMvc.perform(as(get(url), EXTERNAL_ID)).andExpect(status().isNotFound());
  }

  @Test
  void servingRequiresAuthentication() throws Exception {
    UUID documentId = seedDocument();
    String json =
        mockMvc
            .perform(as(uploadRequest(documentId, PNG_BYTES, "s.png"), AUDITOR_ID))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = JsonPath.read(json, "$.url");

    mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
  }

  @Test
  void uploadedFileNameIsStrippedToItsBaseName() throws Exception {
    UUID documentId = seedDocument();

    mockMvc
        .perform(as(uploadRequest(documentId, PNG_BYTES, "../../etc/passwd.png"), AUDITOR_ID))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fileName").value("passwd.png"));
  }
}
