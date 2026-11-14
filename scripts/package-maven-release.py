#!/usr/bin/env python3
"""Package the six locally published SDK modules into a standalone Maven repository."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--maven-local", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--output", type=Path, default=Path("build/releases"))
    args = parser.parse_args()
    version = args.version
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        parser.error("version must be a stable major.minor.patch version")
    modules = [base + suffix for base in ("kmp-updater", "kmp-updater-compose")
               for suffix in ("", "-android", "-jvm")]
    target = args.output / f"kmp-updater-{version}-maven"
    if target.exists():
        shutil.rmtree(target)
    for module in modules:
        source = args.maven_local / "io/github/zerofancy" / module / version
        destination = target / "io/github/zerofancy" / module / version
        if not source.is_dir():
            raise SystemExit(f"Publish to Maven Local first: missing {module}:{version}")
        shutil.copytree(source, destination)
        for extension in ("pom", "module", "jar" if not module.endswith("-android") else "aar"):
            if not (destination / f"{module}-{version}.{extension}").is_file():
                raise SystemExit(f"Missing {module}:{version} {extension}")
        for classifier in ("sources", "javadoc"):
            if not (destination / f"{module}-{version}-{classifier}.jar").is_file():
                raise SystemExit(f"Missing {module}:{version} {classifier}")
        metadata = (f'<?xml version="1.0" encoding="UTF-8"?>\n<metadata>'
                    f'<groupId>io.github.zerofancy</groupId><artifactId>{module}</artifactId>'
                    f'<versioning><latest>{version}</latest><release>{version}</release>'
                    f'<versions><version>{version}</version></versions></versioning></metadata>\n')
        (destination.parent / "maven-metadata.xml").write_text(metadata)
    repository = target.resolve()
    for path in target.rglob("*.module"):
        metadata = json.loads(path.read_text())
        if metadata["component"]["version"] != version:
            raise SystemExit(f"Unexpected component version: {path}")
        for variant in metadata.get("variants", []):
            available = variant.get("available-at")
            entries = ([available] if available else []) + variant.get("files", [])
            for entry in entries:
                referenced = (path.parent / entry["url"]).resolve()
                if not referenced.is_relative_to(repository) or not referenced.is_file():
                    raise SystemExit(f"Missing or unsafe metadata reference in {path}")
                if "sha256" in entry and hashlib.sha256(referenced.read_bytes()).hexdigest() != entry["sha256"]:
                    raise SystemExit(f"Metadata checksum mismatch: {referenced}")
            for dependency in variant.get("dependencies", []):
                if dependency.get("group") == "io.github.zerofancy" and dependency.get("version", {}).get("requires") != version:
                    raise SystemExit(f"Unexpected SDK dependency version in {path}")
    for module in ("kmp-updater-jvm", "kmp-updater-android"):
        sources = target / "io/github/zerofancy" / module / version / f"{module}-{version}-sources.jar"
        with zipfile.ZipFile(sources) as archive:
            if not any(name.endswith("JvmDownloadStorage.kt") for name in archive.namelist()):
                raise SystemExit(f"Shared download storage missing from {sources}")
    archive_path = args.output / f"{target.name}.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(target.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(args.output))
    checksum = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    archive_path.with_suffix(".zip.sha256").write_text(f"{checksum}  {archive_path.name}\n")
    print(f"Packaged {len(modules)} SDK modules: {archive_path}")
    print(f"SHA-256: {checksum}")


if __name__ == "__main__":
    main()
