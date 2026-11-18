"""Local integration checks: python tools/test_sync_clipper_android.py <upstream>"""
import hashlib
import shutil
import sys
import tempfile
from pathlib import Path

from sync_clipper_android import LIBRARIES, sync


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(source: Path) -> int:
    repo = Path(__file__).resolve().parents[1]
    bundled = repo / "app/src/main/assets/clip"
    with tempfile.TemporaryDirectory(prefix="android-clipper-test-") as temp:
        root = Path(temp)
        destination = root / "assets"
        destination.mkdir()
        for name in (*LIBRARIES, "site-rules.json"):
            shutil.copy2(bundled / name, destination / name)

        first = sync(source, destination)
        assert set(first) <= set((*LIBRARIES, "site-rules.json")), f"Unexpected changed paths: {first}"
        rule_hash = digest(destination / "site-rules.json")
        assert sync(source, destination) == [], "No-op sync reported changes"
        assert digest(destination / "site-rules.json") == rule_hash, "No-op changed rules bytes"

        library = destination / LIBRARIES[0]
        library.write_bytes(library.read_bytes() + b"\nlocal sentinel\n")
        changed = sync(source, destination)
        assert LIBRARIES[0] in changed, "Changed library was not restored"
        assert b"local sentinel" not in library.read_bytes()

        bad_source = root / "bad-source"
        shutil.copytree(source / "lib", bad_source / "lib")
        content = (source / "content.js").read_text(encoding="utf-8")
        content = content.replace(
            "const SITE_CONTENT_SELECTORS = {",
            'const SITE_CONTENT_SELECTORS = {\n  "sync-test.example": "article",',
            1,
        )
        changed_source = root / "changed-source"
        shutil.copytree(source / "lib", changed_source / "lib")
        (changed_source / "content.js").write_text(content, encoding="utf-8")
        rules_changed = sync(changed_source, destination)
        assert "site-rules.json" in rules_changed, "Changed rule table was not regenerated"
        rules = (destination / "site-rules.json").read_text(encoding="utf-8")
        assert '"sync-test.example": "article"' in rules
        assert sync(changed_source, destination) == [], "Changed rules did not settle to no-op"

        (bad_source / "content.js").write_text("const SITE_CONTENT_SELECTORS = {};\n", encoding="utf-8")
        library.write_bytes(b"must remain untouched")
        before = digest(library)
        try:
            sync(bad_source, destination)
        except ValueError:
            pass
        else:
            raise AssertionError("Incomplete rule tables unexpectedly succeeded")
        assert digest(library) == before, "Failed generation partially wrote assets"

    print("Android clipper sync checks passed: unchanged, changed, and failed generation.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    raise SystemExit(main(Path(sys.argv[1]).resolve()))
