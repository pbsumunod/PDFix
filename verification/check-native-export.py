"""Independent inspection of the actual optimized-app Files export."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "python-tools"))
from pypdf import PdfReader


def fonts(page):
    result = {}
    for name, reference in page["/Resources"]["/Font"].items():
        font = reference.get_object()
        descendant = font.get("/DescendantFonts")
        base = descendant[0].get_object() if descendant else font
        descriptor = base.get("/FontDescriptor")
        programs = {}
        if descriptor:
            for key in ("/FontFile", "/FontFile2", "/FontFile3"):
                stream = descriptor.get_object().get(key)
                if stream:
                    programs[key] = hashlib.sha256(stream.get_object().get_data()).hexdigest()
        result[str(name)] = {"name": str(font.get("/BaseFont")), "programs": programs}
    return result


before_path = ROOT / "native-before.pdf"
after_path = ROOT / "native-after.pdf"
before = PdfReader(before_path)
after = PdfReader(after_path)
old = "Make room for what matters."
new = "Make room for ideas."
old_text = before.pages[0].extract_text()
new_text = after.pages[0].extract_text()
before_fields = {name: str(field.get("/V")) for name, field in before.get_fields().items()}
after_fields = {name: str(field.get("/V")) for name, field in after.get_fields().items()}
checks = {
    "two_pages": len(before.pages) == len(after.pages) == 2,
    "old_text_was_present": old in old_text,
    "old_text_removed": old not in new_text,
    "replacement_present": new in new_text,
    # Readers can infer trailing whitespace from the retained text advance.
    "all_other_page_one_words_preserved": old_text.replace(old, new).split() == new_text.split(),
    "font_names_and_programs_unchanged": fonts(before.pages[0]) == fonts(after.pages[0]),
    "second_page_content_unchanged": before.pages[1].get_contents().get_data() == after.pages[1].get_contents().get_data(),
    "three_form_values_unchanged": len(after_fields) == 3 and before_fields == after_fields,
}
result = {
    "version": "1.1.0", "parser": "pypdf 6.19.0", "source": "Actual Android Files exports from optimized validation app",
    "before_sha256": hashlib.sha256(before_path.read_bytes()).hexdigest(),
    "after_sha256": hashlib.sha256(after_path.read_bytes()).hexdigest(),
    "bytes": after_path.stat().st_size, "checks": checks, "fonts": fonts(after.pages[0]), "form_values": after_fields,
}
(ROOT / "optimized-export-check.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
assert all(checks.values()), "An optimized export preservation check failed"
