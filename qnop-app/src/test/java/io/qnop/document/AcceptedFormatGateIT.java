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
package io.qnop.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.spi.extract.DocumentExtractor;
import io.qnop.spi.extract.RenderedDocument;
import io.qnop.spi.extract.Surface;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The accepted-format gate derives from the registered extractors (issue #601, ADR-0032 amendment)
 * — proven with the test-only fake extractor the seam issues require: its claimed type passes the
 * gate and reaches the job pipeline, appears in the advertised media types, and an unclaimed (but
 * recognized) type stays a 415.
 */
@AutoConfigureMockMvc
@Import(AcceptedFormatGateIT.PngExtractor.class)
class AcceptedFormatGateIT extends SeededIntegrationTest {

  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };
  private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 1, 2};

  @TestConfiguration
  static class PngExtractor {
    @Bean
    DocumentExtractor pngDocumentExtractor() {
      return new DocumentExtractor() {
        @Override
        public boolean supports(String contentType) {
          return "image/png".equals(contentType);
        }

        @Override
        public Set<String> mediaTypes() {
          return Set.of("image/png");
        }

        @Override
        public RenderedDocument extract(InputStream content) {
          // An image has a surface and no text spans.
          return new RenderedDocument(List.of(new Surface(0, 100, 100, List.of())));
        }
      };
    }
  }

  @Autowired MockMvc mockMvc;

  @Test
  void aClaimedTypeIsAdvertisedAcceptedAndEnqueued() throws Exception {
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supportedMediaTypes", Matchers.hasItem("image/png")))
        .andExpect(jsonPath("$.supportedMediaTypes", Matchers.hasItem("application/pdf")));

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "scan.png", "image/png", PNG))
                .param("title", "Scanned agreement")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isCreated())
        // Enqueued into the extraction pipeline, not accepted-and-forgotten.
        .andExpect(
            jsonPath(
                "$.extractionStatus",
                Matchers.anyOf(
                    Matchers.is("PENDING"), Matchers.is("RUNNING"), Matchers.is("READY"))));
  }

  @Test
  void anUnclaimedRecognizedTypeStaysTheStandard415() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "anim.gif", "image/gif", GIF))
                .param("title", "Not accepted")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isUnsupportedMediaType());
  }
}
