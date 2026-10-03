#!/usr/bin/env python3
"""Create a deterministic, allowlisted source ZIP. Python standard library only.

Run from any directory: python scripts/package-source.py
Output: <project>/delivery/PDFix-source.zip

Unknown files, local publisher/signing configuration, build output, dependency
caches, logs, device dumps, emulator configuration, and delivery output are not
packaged. Keep the allowlists explicit when adding new distributable files.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import stat
import tempfile
import zipfile


PROJECT_ROOT = Path(__file__).resolve().parent.parent
ARCHIVE_ROOT = "PDFix"
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

EXACT_FILES = frozenset({
    ".gitignore",
    "README.md",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "release-version.properties",
    "publisher.properties.example",
    "signing.properties.example",
    "gradlew",
    "gradlew.bat",
    "Build Android App.cmd",
    "Build Play Store Bundle.cmd",
    "Build First Play Upload.cmd",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "app/test-proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "scripts/build.ps1",
    "scripts/build.sh",
    "scripts/install-app.ps1",
    "scripts/Install on Phone.cmd",
    "scripts/artwork.py",
    "scripts/verify-artifact.py",
    "scripts/package-source.py",
    "release/PRODUCT.md",
    "release/PLAY-STORE.md",
    "release/BILLING.md",
    "release/FONT-LICENSE.txt",
    "release/privacy-policy.html",
    "release/store-listing.md",
    "release/artwork/README.md",
    "release/artwork/play-icon-512.svg",
    "release/artwork/play-icon-512.png",
    "release/artwork/feature-graphic-1024x500.svg",
    "release/artwork/feature-graphic-1024x500.png",
    "verification/README.md",
    "verification/SUMMARY.md",
    "verification/RESULTS.md",
    "verification/TEST-RESULTS.md",
    "verification/TEST-REPORT.md",
    "verification/artifact-debug.json",
    "verification/artifact-validation.json",
    "verification/optimized-export-check.json",
    "verification/final-build-artifacts.json",
    "verification/instrumentation-debug-artifacts.json",
    "verification/emulator/environment.json",
    "verification/emulator/environment-text-edit.json",
    "verification/screenshots/README.md",
})

# Only these source/resource trees and extensions are distributable. In
# particular, arbitrary test documents or asset databases are not included.
SOURCE_TREES = {
    "app/src/main/java": frozenset({".kt", ".java"}),
    "app/src/test/java": frozenset({".kt", ".java"}),
    "app/src/androidTest/java": frozenset({".kt", ".java"}),
    "app/src/main/res": frozenset({".xml", ".png", ".webp", ".jpg", ".jpeg"}),
    "app/src/main/assets/fonts": frozenset({".ttf", ".otf"}),
    "app/src/main/assets/licenses": frozenset({".txt"}),
    "verification/screenshots": frozenset({".png", ".jpg", ".jpeg", ".webp"}),
}

BLOCKED_PARTS = frozenset({
    ".git", ".gradle", ".kotlin", ".idea", ".codex", ".agents",
    "build", "delivery", "__pycache__", "node_modules",
})
BLOCKED_NAMES = frozenset({
    "local.properties", "publisher.properties", "signing.properties",
    ".env", "credentials.json", "service-account.json", "google-services.json",
})
BLOCKED_SUFFIXES = frozenset({".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"})


def is_link(path: Path) -> bool:
    return path.is_symlink() or getattr(path, "is_junction", lambda: False)()


def safe_relative(path: Path) -> str:
    """Fail closed if a selected entry traverses a link or leaves the project."""
    relative = path.relative_to(PROJECT_ROOT)
    if any(part.lower() in BLOCKED_PARTS for part in relative.parts):
        raise ValueError(f"Blocked packaging path: {relative.as_posix()}")
    if path.name.lower() in BLOCKED_NAMES or path.suffix.lower() in BLOCKED_SUFFIXES:
        raise ValueError(f"Blocked private file: {relative.as_posix()}")
    cursor = PROJECT_ROOT
    for part in relative.parts:
        cursor = cursor / part
        if is_link(cursor):
            raise ValueError(f"Links are not packaged: {relative.as_posix()}")
    path.resolve(strict=True).relative_to(PROJECT_ROOT)
    if not stat.S_ISREG(path.stat().st_mode):
        raise ValueError(f"Not a regular source file: {relative.as_posix()}")
    return relative.as_posix()


def selected_files() -> list[tuple[str, Path]]:
    selected: dict[str, Path] = {}
    for relative in EXACT_FILES:
        candidate = PROJECT_ROOT / relative
        if candidate.exists() or is_link(candidate):
            selected[safe_relative(candidate)] = candidate
    for tree, extensions in SOURCE_TREES.items():
        directory = PROJECT_ROOT / tree
        if not directory.exists():
            continue
        if is_link(directory):
            raise ValueError(f"Source tree must not be a link: {tree}")
        for current, directories, filenames in os.walk(directory, followlinks=False):
            base = Path(current)
            directories[:] = sorted(name for name in directories
                                    if name.lower() not in BLOCKED_PARTS
                                    and not name.startswith(".")
                                    and not is_link(base / name))
            for name in sorted(filenames):
                candidate = base / name
                if candidate.suffix.lower() in extensions and not name.startswith("."):
                    selected[safe_relative(candidate)] = candidate
    required = {"README.md", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar", "scripts/package-source.py"}
    missing = required.difference(selected)
    if missing:
        raise RuntimeError("Required source files missing: " + ", ".join(sorted(missing)))
    return sorted(selected.items())


def write_entry(archive: zipfile.ZipFile, relative: str, content: bytes) -> None:
    entry = zipfile.ZipInfo(f"{ARCHIVE_ROOT}/{relative}", date_time=FIXED_TIMESTAMP)
    entry.create_system = 3  # Fixed Unix metadata, independent of the host OS.
    permissions = 0o755 if relative in {"gradlew", "scripts/build.sh"} else 0o644
    entry.external_attr = (stat.S_IFREG | permissions) << 16
    entry.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(entry, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def main() -> None:
    files = selected_files()
    delivery = PROJECT_ROOT / "delivery"
    if is_link(delivery):
        raise ValueError("The delivery directory must not be a link.")
    delivery.mkdir(exist_ok=True)
    delivery.resolve(strict=True).relative_to(PROJECT_ROOT)
    destination = delivery / "PDFix-source.zip"
    if is_link(destination):
        raise ValueError("The output ZIP must not be a link.")

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(prefix="source-", suffix=".zip.tmp", dir=delivery, delete=False) as temporary:
            temporary_path = Path(temporary.name)
        checksums: list[str] = []
        with zipfile.ZipFile(temporary_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for relative, path in files:
                # Recheck before reading in case the tree changed during selection.
                safe_relative(path)
                content = path.read_bytes()
                write_entry(archive, relative, content)
                checksums.append(f"{hashlib.sha256(content).hexdigest()}  {relative}")
            write_entry(archive, "SOURCE-CHECKSUMS.sha256", ("\n".join(checksums) + "\n").encode("utf-8"))
        with temporary_path.open("r+b") as handle:
            os.fsync(handle.fileno())
        os.replace(temporary_path, destination)
        temporary_path = None
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        print(f"Packaged {len(files)} allowlisted files plus SOURCE-CHECKSUMS.sha256")
        print(f"Output: {destination}")
        print(f"SHA-256: {digest}")
        print("No local publisher/signing settings, keys, build outputs, logs, or caches were included.")
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
