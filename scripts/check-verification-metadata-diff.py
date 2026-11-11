#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
#
# Gate for automated updates of gradle/verification-metadata.xml (issue #925).
#
#   check-verification-metadata-diff.py <committed.xml> <candidate.xml>
#
# The candidate is produced by a job that runs branch-controlled build code, so
# before a privileged job commits it, this checker allows exactly one kind of
# change: ADDING checksums. Anything that could weaken verification fails:
#
#  - any change to <configuration> (verify-metadata, verify-signatures,
#    trusted-artifacts, trusted-keys, ignored-keys, key-servers, ...)
#  - any other top-level element that differs from the committed file
#  - a removed component, a removed artifact, or any change to an existing
#    artifact (checksum changed, added or removed, alternative entries, keys)
#  - added entries carrying anything but <artifact name> / <sha256 value>
#  - a document that is not well-formed, declares a DTD/entities, or is not a
#    Gradle verification-metadata document
#
# Stdlib only (run with `python3 -I`). ElementTree does not resolve external
# entities; the DOCTYPE refusal below additionally rules out entity expansion.
# Exit status: 0 = acceptable, 1 = rejected (reasons on stderr), 2 = usage.
import sys
import xml.etree.ElementTree as ET

NS = "{https://schema.gradle.org/dependency-verification}"
ROOT_TAG = NS + "verification-metadata"
SHA256_ATTRS = {"value", "origin", "reason"}


class Rejected(Exception):
    pass


def parse(path):
    with open(path, "rb") as f:
        raw = f.read()
    if b"<!DOCTYPE" in raw or b"<!ENTITY" in raw:
        raise Rejected(f"{path}: DTD/entity declarations are not allowed")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as e:
        raise Rejected(f"{path}: not well-formed XML: {e}") from None
    if root.tag != ROOT_TAG:
        raise Rejected(f"{path}: root element is {root.tag}, expected {ROOT_TAG}")
    return root


def canonical(element):
    """Order-sensitive, whitespace-insensitive serialization of a subtree."""
    if element is None:
        return None
    attrs = sorted(element.attrib.items())
    text = (element.text or "").strip()
    children = [canonical(child) for child in element]
    return (element.tag, attrs, text, children)


def components(root, path):
    """Map (group, name, version) -> component element; rejects duplicates."""
    section = root.find(NS + "components")
    result = {}
    if section is None:
        return result
    for component in section:
        if component.tag != NS + "component":
            raise Rejected(f"{path}: unexpected element {component.tag} in <components>")
        key = tuple(component.get(a) for a in ("group", "name", "version"))
        if key in result:
            raise Rejected(f"{path}: duplicate component {':'.join(map(str, key))}")
        result[key] = component
    return result


def artifacts(component, path):
    result = {}
    for artifact in component:
        if artifact.tag != NS + "artifact":
            raise Rejected(
                f"{path}: component {gav(component)} has unexpected child {artifact.tag}"
            )
        name = artifact.get("name")
        if not name or name in result:
            raise Rejected(f"{path}: missing or duplicate artifact name in {gav(component)}")
        result[name] = artifact
    return result


def gav(component):
    return ":".join(component.get(a) or "?" for a in ("group", "name", "version"))


def check_added_artifact(artifact, where):
    if set(artifact.attrib) != {"name"}:
        raise Rejected(f"added artifact {where} carries attributes {sorted(artifact.attrib)}")
    children = list(artifact)
    if not children:
        raise Rejected(f"added artifact {where} has no checksum")
    for child in children:
        if child.tag != NS + "sha256":
            raise Rejected(f"added artifact {where} carries <{child.tag}>, only sha256 is allowed")
        if not set(child.attrib) <= SHA256_ATTRS or len(list(child)):
            raise Rejected(f"added artifact {where} has an unexpected sha256 entry")
        value = child.get("value") or ""
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            raise Rejected(f"added artifact {where} has a malformed sha256 value")


def check(committed_path, candidate_path):
    committed = parse(committed_path)
    candidate = parse(candidate_path)

    if sorted(committed.attrib.items()) != sorted(candidate.attrib.items()):
        raise Rejected("root element attributes changed")

    # Every top-level section except <components> must be byte-for-byte the same
    # (modulo whitespace) — <configuration> above all.
    tags = {child.tag for child in committed} | {child.tag for child in candidate}
    for tag in sorted(tags - {NS + "components"}):
        old = [canonical(e) for e in committed.findall(tag)]
        new = [canonical(e) for e in candidate.findall(tag)]
        if old != new:
            raise Rejected(f"<{tag.removeprefix(NS)}> changed — only added checksums are allowed")

    old_components = components(committed, committed_path)
    new_components = components(candidate, candidate_path)

    for key, old in old_components.items():
        new = new_components.get(key)
        if new is None:
            raise Rejected(f"component {gav(old)} was removed")
        if sorted(old.attrib.items()) != sorted(new.attrib.items()):
            raise Rejected(f"component {gav(old)} attributes changed")
        old_artifacts = artifacts(old, committed_path)
        new_artifacts = artifacts(new, candidate_path)
        for name, artifact in old_artifacts.items():
            if name not in new_artifacts:
                raise Rejected(f"artifact {gav(old)}/{name} was removed")
            if canonical(artifact) != canonical(new_artifacts[name]):
                raise Rejected(f"artifact {gav(old)}/{name} changed")
        for name in new_artifacts.keys() - old_artifacts.keys():
            check_added_artifact(new_artifacts[name], f"{gav(old)}/{name}")

    for key in new_components.keys() - old_components.keys():
        component = new_components[key]
        if set(component.attrib) != {"group", "name", "version"} or not all(key):
            raise Rejected(f"added component {gav(component)} has unexpected attributes")
        added = artifacts(component, candidate_path)
        if not added:
            raise Rejected(f"added component {gav(component)} has no artifacts")
        for name, artifact in added.items():
            check_added_artifact(artifact, f"{gav(component)}/{name}")

    return len(new_components) - len(old_components)


def main(argv):
    if len(argv) != 3:
        print(__doc__ or "usage: check-verification-metadata-diff.py <committed> <candidate>",
              file=sys.stderr)
        return 2
    try:
        added = check(argv[1], argv[2])
    except (Rejected, OSError) as e:
        print(f"::error::verification metadata rejected: {e}", file=sys.stderr)
        return 1
    print(f"verification metadata accepted: {added} component(s) added, nothing else changed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
