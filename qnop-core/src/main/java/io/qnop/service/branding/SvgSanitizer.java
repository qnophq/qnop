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
package io.qnop.service.branding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Allowlist SVG sanitizer (issue #23). SVG is XML that browsers execute, so an uploaded logo is an
 * XSS/XXE vector unless neutralized. This sanitizer:
 *
 * <ul>
 *   <li>parses with an <strong>XXE-hardened</strong> parser — DOCTYPEs are rejected outright and
 *       all external entity/DTD/stylesheet access is disabled, so entity-expansion and file/SSRF
 *       disclosure are impossible;
 *   <li>keeps only an <strong>allowlist</strong> of presentational elements and drops everything
 *       else (so {@code <script>}, {@code <foreignObject>}, {@code <style>}, {@code <image>},
 *       {@code <a>} … never survive);
 *   <li>strips {@code on*} event-handler attributes, inline {@code style}, and any {@code href} /
 *       {@code xlink:href} that is not a local {@code #fragment}.
 * </ul>
 *
 * <p>Pure, dependency-free, DB-free logic (ADR-0004). Throws {@link IllegalArgumentException} when
 * the input is not a well-formed {@code <svg>} document.
 */
public final class SvgSanitizer {

  /**
   * Maximum element nesting depth. A legitimate logo is shallow; a pathologically deep tree is a
   * denial-of-service vector (stack exhaustion in the recursive walk), so it is rejected outright
   * (issue #48).
   */
  private static final int MAX_NESTING_DEPTH = 100;

  private static final Set<String> ALLOWED_ELEMENTS =
      Set.of(
          "svg",
          "g",
          "defs",
          "title",
          "desc",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "text",
          "tspan",
          "use",
          "symbol",
          "linearGradient",
          "radialGradient",
          "stop",
          "clipPath",
          "mask",
          "pattern",
          "marker");

  private SvgSanitizer() {}

  /**
   * Returns a sanitized copy of the SVG bytes.
   *
   * @throws IllegalArgumentException if the input is not a well-formed, DOCTYPE-free {@code <svg>}
   */
  public static byte[] sanitize(byte[] svg) {
    try {
      Document document = parseHardened(svg);
      Element root = document.getDocumentElement();
      if (root == null || !"svg".equals(localName(root))) {
        throw new IllegalArgumentException("not an <svg> document");
      }
      sanitizeElement(root, 0);
      return serialize(document);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid or unsafe SVG: " + e.getMessage());
    }
  }

  private static Document parseHardened(byte[] svg) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setExpandEntityReferences(false);
    factory.setXIncludeAware(false);
    // The single most important control: no DOCTYPE means no XXE, no entity expansion.
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new ByteArrayInputStream(svg));
  }

  private static void sanitizeElement(Element element, int depth) {
    if (depth > MAX_NESTING_DEPTH) {
      throw new IllegalArgumentException(
          "SVG nesting exceeds the maximum depth of " + MAX_NESTING_DEPTH);
    }
    NamedNodeMap attributes = element.getAttributes();
    List<Attr> toRemove = new ArrayList<>();
    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);
      String name = localName(attribute).toLowerCase(Locale.ROOT);
      if (name.startsWith("on")) {
        toRemove.add(attribute);
      } else if (name.equals("style")) {
        toRemove.add(attribute);
      } else if (name.equals("href")) {
        String value = attribute.getValue() == null ? "" : attribute.getValue().trim();
        if (!value.startsWith("#")) {
          toRemove.add(attribute); // only local fragment references survive
        }
      }
    }
    for (Attr attribute : toRemove) {
      element.removeAttributeNode(attribute);
    }

    List<Element> childElements = new ArrayList<>();
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE) {
        childElements.add((Element) child);
      }
    }
    for (Element child : childElements) {
      if (ALLOWED_ELEMENTS.contains(localName(child))) {
        sanitizeElement(child, depth + 1);
      } else {
        element.removeChild(child); // drops <script>, <foreignObject>, <style>, <image>, …
      }
    }
  }

  private static String localName(Node node) {
    return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
  }

  private static byte[] serialize(Document document) throws Exception {
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(document), new StreamResult(out));
    return out.toByteArray();
  }
}
