#!/usr/bin/env python3
"""Remove credential-shaped text from browser diagnostics before publication."""

import os
import re
import sys
import tempfile
import zipfile
from pathlib import Path


TOKEN = re.compile(
    rb"Bearer [A-Za-z0-9._-]{20,}|(?:[A-Za-z0-9_-]{10,}\.){2}[A-Za-z0-9_-]{10,}"
)
PRIVATE_KEY = re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----")
REDACTED = b"[REDACTED CREDENTIAL]"


def redact(data: bytes, source: Path) -> bytes:
    if PRIVATE_KEY.search(data):
        raise ValueError(f"private key found in {source}")
    return TOKEN.sub(REDACTED, data)


def redact_zip(path: Path) -> None:
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(path) as original, zipfile.ZipFile(temporary_path, "w") as sanitized:
            for member in original.infolist():
                sanitized.writestr(member, redact(original.read(member), path))
        with zipfile.ZipFile(temporary_path) as sanitized:
            if sanitized.testzip() is not None:
                raise ValueError(f"redacted trace archive is corrupt: {path}")
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def main(root: Path) -> None:
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() == ".zip":
            redact_zip(path)
        else:
            original = path.read_bytes()
            sanitized = redact(original, path)
            if sanitized != original:
                path.write_bytes(sanitized)


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"capture-browser-e2e-diagnostics: {error}", file=sys.stderr)
        sys.exit(1)
