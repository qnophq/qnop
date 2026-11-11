#!/usr/bin/env python3
# Copyright (c) 2026-present devtank42 GmbH
#
# This file is part of qnop (Qualified Notes on Papers).
#
# qnop is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option)
# any later version.
#
# qnop is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with qnop. If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only
"""Writes testdata/documents/sample.docx, the plain-text DOCX the smoke test ingests.

The archive is byte-for-byte reproducible: fixed member order, fixed timestamps,
fixed compression. Regenerate with `python3 scripts/make-sample-docx.py`.
"""

import pathlib
import zipfile

TEXT = "QNOP DOCX smoke fixture"

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>
"""

RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
"""

DOCUMENT = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
<w:p><w:r><w:t>{TEXT}</w:t></w:r></w:p>
</w:body>
</w:document>
"""

MEMBERS = [
    ("[Content_Types].xml", CONTENT_TYPES),
    ("_rels/.rels", RELS),
    ("word/document.xml", DOCUMENT),
]


def main() -> None:
    target = pathlib.Path(__file__).resolve().parent.parent / "testdata/documents/sample.docx"
    with zipfile.ZipFile(target, "w") as archive:
        for name, content in MEMBERS:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, content.encode("utf-8"))
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
