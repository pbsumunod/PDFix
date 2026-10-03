#!/usr/bin/env python3
"""Inspect a built APK without installing it or requiring Android SDK utilities.

Checks binary manifest policy, known ad SDK class markers, 64-bit ELF/ZIP 16 KB
alignment, and optional exact native-library inventory. Does not establish runtime
compatibility, purchase configuration, signing provenance, or absence of every SDK.

Example:
  python3 scripts/verify-artifact.py app/build/outputs/apk/validation/app-validation.apk \
      --package com.sumclartech.pdfix.validation --release \
      --output verification/artifact-validation.json

References:
  https://developer.android.com/guide/practices/page-sizes
  https://android.googlesource.com/platform/frameworks/base/+/master/libs/androidfw/include/androidfw/ResourceTypes.h
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
from typing import Any
import zipfile


PAGE_SIZE = 16_384
NO_STRING = 0xFFFFFFFF
FORBIDDEN_PERMISSIONS = {
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_TOPICS",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_CONTACTS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
}
AD_SDK_MARKERS = {
    "Google Mobile Ads": b"Lcom/google/android/gms/ads/MobileAds;",
    "Google AdView": b"Lcom/google/android/gms/ads/AdView;",
    "Meta Audience Network": b"Lcom/facebook/ads/AdView;",
    "AppLovin": b"Lcom/applovin/sdk/AppLovinSdk;",
    "Unity Ads": b"Lcom/unity3d/ads/UnityAds;",
    "ironSource": b"Lcom/ironsource/mediationsdk/IronSource;",
}


def unpack(data: bytes, fmt: str, offset: int) -> tuple:
    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise ValueError("Truncated binary structure")
    return struct.unpack_from(fmt, data, offset)


def decode_length8(data: bytes, offset: int) -> tuple[int, int]:
    value = data[offset]
    if value & 0x80:
        return ((value & 0x7F) << 8) | data[offset + 1], offset + 2
    return value, offset + 1


def decode_length16(data: bytes, offset: int) -> tuple[int, int]:
    value = unpack(data, "<H", offset)[0]
    if value & 0x8000:
        return ((value & 0x7FFF) << 16) | unpack(data, "<H", offset + 2)[0], offset + 4
    return value, offset + 2


def string_pool(data: bytes, chunk: int, header_size: int) -> list[str]:
    count, _, flags, strings_start, _ = unpack(data, "<IIIII", chunk + 8)
    if count > len(data) // 4:
        raise ValueError("Invalid string pool count")
    offsets = unpack(data, "<" + "I" * count, chunk + header_size)
    result = []
    for relative in offsets:
        start = chunk + strings_start + relative
        if flags & 0x100:
            _, start = decode_length8(data, start)  # UTF-16 character count
            length, start = decode_length8(data, start)
            value = data[start:start + length].decode("utf-8")
        else:
            length, start = decode_length16(data, start)
            value = data[start:start + length * 2].decode("utf-16-le")
        result.append(value)
    return result


def parse_manifest(data: bytes) -> list[dict[str, Any]]:
    kind, header_size, total_size = unpack(data, "<HHI", 0)
    if kind != 0x0003 or total_size != len(data):
        raise ValueError("Expected an APK binary AndroidManifest.xml (not App Bundle protobuf)")
    strings: list[str] = []
    elements = []
    offset = header_size
    while offset < total_size:
        chunk_type, chunk_header, chunk_size = unpack(data, "<HHI", offset)
        if chunk_size < chunk_header or chunk_size < 8 or offset + chunk_size > total_size:
            raise ValueError("Invalid binary XML chunk size")
        if chunk_type == 0x0001:
            strings = string_pool(data, offset, chunk_header)
        elif chunk_type == 0x0102:
            _, name_index, attribute_start, attribute_size, count = unpack(data, "<IIHHH", offset + 16)
            attributes = {}
            if attribute_size < 20:
                raise ValueError("Invalid binary XML attribute size")
            for index in range(count):
                at = offset + 16 + attribute_start + index * attribute_size
                _, attr_name, raw, _, _, value_type, value = unpack(data, "<IIIHBBI", at)
                if raw != NO_STRING:
                    decoded: Any = strings[raw]
                elif value_type == 0x03:
                    decoded = strings[value]
                elif value_type == 0x12:
                    decoded = value != 0
                elif value_type in (0x10, 0x11):
                    decoded = value
                else:
                    decoded = f"@0x{value:08x}"
                attributes[strings[attr_name]] = decoded
            elements.append({"tag": strings[name_index], "attributes": attributes})
        offset += chunk_size
    return elements


def inspect_elf(data: bytes) -> dict[str, Any]:
    if data[:4] != b"\x7fELF" or len(data) < 52:
        raise ValueError("Native library is not a valid ELF file")
    elf_class, byte_order = data[4], data[5]
    if elf_class not in (1, 2) or byte_order not in (1, 2):
        raise ValueError("Unsupported ELF class or byte order")
    endian = "<" if byte_order == 1 else ">"
    is_64 = elf_class == 2
    ph_offset = unpack(data, endian + ("Q" if is_64 else "I"), 32 if is_64 else 28)[0]
    ph_size, ph_count = unpack(data, endian + "HH", 54 if is_64 else 42)
    minimum_size = 56 if is_64 else 32
    if ph_size < minimum_size or ph_count == 0:
        raise ValueError("ELF has no usable program header table")
    load_segments = []
    relro_segments = []
    for index in range(ph_count):
        at = ph_offset + index * ph_size
        if is_64:
            p_type, p_flags, p_offset, p_vaddr, _, _, p_memsz, p_align = unpack(data, endian + "IIQQQQQQ", at)
        else:
            p_type, p_offset, p_vaddr, _, _, p_memsz, p_flags, p_align = unpack(data, endian + "IIIIIIII", at)
        if p_type == 1:
            load_segments.append({
                "alignment": p_align,
                "offset": p_offset,
                "virtual_address": p_vaddr,
                "memory_size": p_memsz,
                "writable": bool(p_flags & 2),
                "aligned_16kb": p_align >= PAGE_SIZE and (p_offset - p_vaddr) % PAGE_SIZE == 0,
            })
        elif p_type == 0x6474E552:
            relro_segments.append({
                "virtual_address": p_vaddr,
                "memory_size": p_memsz,
                "end_aligned_16kb": (p_vaddr + p_memsz) % PAGE_SIZE == 0,
            })
    if not load_segments:
        raise ValueError("ELF has no loadable segments")
    for segment in relro_segments:
        start = segment["virtual_address"]
        end = start + segment["memory_size"]
        # Linkers can leave an unmapped hole after an unaligned RELRO end. Rounding
        # across that hole is harmless; reject when the rounded protection would
        # cover actual writable LOAD bytes outside the requested RELRO interval.
        padding = ((start // PAGE_SIZE * PAGE_SIZE, start),
                   (end, (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE))
        segment["page_padding_overlaps_writable_load"] = any(
            max(left, load["virtual_address"]) < min(right, load["virtual_address"] + load["memory_size"])
            for left, right in padding for load in load_segments if load["writable"]
        )
    return {
        "bits": 64 if is_64 else 32,
        "load_segments": load_segments,
        "relro_segments": relro_segments,
        "elf_aligned_16kb": all(s["aligned_16kb"] for s in load_segments),
        "relro_aligned_16kb": all(s["end_aligned_16kb"] for s in relro_segments),
        "relro_page_padding_safe": not any(s["page_padding_overlaps_writable_load"] for s in relro_segments),
    }


def local_data_offset(apk, entry: zipfile.ZipInfo) -> int:
    apk.seek(entry.header_offset)
    header = apk.read(30)
    if header[:4] != b"PK\x03\x04":
        raise ValueError("Invalid ZIP local file header")
    name_length, extra_length = unpack(header, "<HH", 26)
    return entry.header_offset + 30 + name_length + extra_length


def check_artifact(args: argparse.Namespace) -> dict[str, Any]:
    path = args.apk.resolve(strict=True)
    failures: list[str] = []
    warnings: list[str] = []
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    report: dict[str, Any] = {
        "artifact": str(path), "sha256": digest.hexdigest(), "size_bytes": path.stat().st_size,
        "failures": failures, "warnings": warnings,
    }
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        names = archive.namelist()
        if len(names) != len(set(names)):
            failures.append("APK contains duplicate ZIP entry names")
        manifest = parse_manifest(archive.read("AndroidManifest.xml"))
        root = next(node["attributes"] for node in manifest if node["tag"] == "manifest")
        app = next(node["attributes"] for node in manifest if node["tag"] == "application")
        sdk = next(node["attributes"] for node in manifest if node["tag"] == "uses-sdk")
        permissions = sorted({
            node["attributes"]["name"] for node in manifest
            if node["tag"] in ("uses-permission", "uses-permission-sdk-23")
        })
        report["manifest"] = {"package": root.get("package"), "sdk": sdk, "application": app, "permissions": permissions}
        if root.get("package") != args.package:
            failures.append(f"Unexpected package: {root.get('package')!r}; expected {args.package!r}")
        if int(sdk.get("targetSdkVersion", 0)) < args.minimum_target_sdk:
            failures.append(f"Target SDK is below {args.minimum_target_sdk}")
        if app.get("allowBackup", True) is not False:
            failures.append("Application must explicitly disable backup")
        if app.get("usesCleartextTraffic", True) is not False:
            failures.append("Application must explicitly disable cleartext traffic")
        if args.release and app.get("debuggable", False) is not False:
            failures.append("Release artifact is debuggable")
        if "com.android.vending.BILLING" not in permissions:
            failures.append("Play Billing permission is absent")
        blocked = sorted(FORBIDDEN_PERMISSIONS.intersection(permissions))
        if blocked:
            failures.append("Unexpected advertising or broad data permissions: " + ", ".join(blocked))
        providers = [node["attributes"] for node in manifest if node["tag"] == "provider"]
        file_providers = [node for node in providers if node.get("authorities") == args.package + ".files"]
        if not file_providers or any(node.get("exported", True) is not False for node in file_providers):
            failures.append("App FileProvider must exist and must not be exported")
        if any(node.get("grantUriPermissions") is not True for node in file_providers):
            failures.append("App FileProvider must permit temporary URI grants")

        marker_hits = set()
        dex_files = [name for name in names if re.fullmatch(r"classes\d*\.dex", name)]
        for name in dex_files:
            dex = archive.read(name)
            marker_hits.update(label for label, marker in AD_SDK_MARKERS.items() if marker in dex)
        report["ad_sdk_marker_hits"] = sorted(marker_hits)
        report["dex_file_count"] = len(dex_files)
        if not dex_files:
            failures.append("No executable DEX files found")
        if marker_hits:
            failures.append("Known advertising SDK class markers found: " + ", ".join(sorted(marker_hits)))

        native = []
        for entry in archive.infolist():
            if not re.fullmatch(r"lib/[^/]+/[^/]+\.so", entry.filename):
                continue
            details = inspect_elf(archive.read(entry))
            offset = local_data_offset(raw, entry)
            stored = entry.compress_type == zipfile.ZIP_STORED
            details.update({"path": entry.filename, "size_bytes": entry.file_size,
                            "zip_uncompressed": stored, "zip_data_offset": offset,
                            "zip_aligned_16kb": offset % PAGE_SIZE == 0 if stored else None})
            native.append(details)
            if details["bits"] == 64:
                if not details["elf_aligned_16kb"]:
                    failures.append(f"64-bit ELF LOAD segment is not 16 KB aligned: {entry.filename}")
                if not details["relro_page_padding_safe"]:
                    failures.append(f"64-bit ELF RELRO rounding overlaps writable memory: {entry.filename}")
                if stored and offset % PAGE_SIZE != 0:
                    failures.append(f"Uncompressed 64-bit library is not 16 KB ZIP aligned: {entry.filename}")
        report["native_libraries"] = native
        report["native_library_names"] = sorted({item["path"].rsplit("/", 1)[-1] for item in native})
        report["native_abis"] = sorted({item["path"].split("/")[1] for item in native})
        if native and not any(item["bits"] == 64 for item in native):
            failures.append("APK contains native code but no 64-bit native libraries")
        if args.expected_native_library is not None:
            expected = set(args.expected_native_library)
            actual = set(report["native_library_names"])
            if actual != expected:
                failures.append(f"Native inventory mismatch: missing {sorted(expected - actual)}, unexpected {sorted(actual - expected)}")
        else:
            warnings.append("Native library inventory reported; no exact expected-library list supplied")
    warnings.append("Ad SDK marker scan covers known entry points only; review the dependency tree too")
    warnings.append("Static alignment checks do not replace testing on a 16 KB Android device/emulator")
    warnings.append("APK signing provenance and Play billing configuration require separate verification")
    report["passed"] = not failures
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--package", default="com.sumclartech.pdfix")
    parser.add_argument("--minimum-target-sdk", type=int, default=36)
    parser.add_argument("--release", action="store_true", help="Also reject debuggable artifacts")
    parser.add_argument("--expected-native-library", action="append", help="Repeat for exact set of .so basenames")
    parser.add_argument("--output", type=Path, help="Write machine-readable JSON report")
    args = parser.parse_args()
    try:
        report = check_artifact(args)
    except (OSError, ValueError, KeyError, IndexError, StopIteration, struct.error, zipfile.BadZipFile) as error:
        report = {"artifact": str(args.apk), "passed": False, "failures": [str(error) or type(error).__name__]}
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
