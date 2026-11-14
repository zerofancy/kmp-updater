#!/usr/bin/env python3
"""Keep KMP source variants usable after JitPack removes artifact classifiers."""
import hashlib
import json
from pathlib import Path
import shutil
import sys


def main():
    group, version = sys.argv[1:]
    repository = Path.home() / ".m2/repository" / group.replace(".", "/")
    for root in ("kmp-updater", "kmp-updater-compose"):
        for suffix in ("", "-android", "-jvm"):
            module = root + suffix
            directory = repository / module / version
            sources = directory / f"{module}-{version}-sources.jar"
            archive_name = f"{module}-{version}.zip"
            metadata_path = directory / f"{module}-{version}.module"
            metadata = json.loads(metadata_path.read_text())
            source_bytes = sources.read_bytes()
            source_variants = 0
            for variant in metadata["variants"]:
                if variant.get("attributes", {}).get("org.gradle.docstype") != "sources":
                    continue
                for artifact in variant.get("files", []):
                    if artifact["size"] != len(source_bytes) or artifact["sha256"] != hashlib.sha256(source_bytes).hexdigest():
                        raise ValueError(f"Source artifact does not match metadata: {module}")
                    artifact["name"] = archive_name
                    artifact["url"] = archive_name
                    source_variants += 1
            if source_variants != 1:
                raise ValueError(f"Expected one source archive for {module}, got {source_variants}")
            # Retain the standard sources JAR for Maven and older IDEs.
            shutil.copyfile(sources, directory / archive_name)
            metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
            print(f"Prepared JitPack sources: {module}:{version}")


if __name__ == "__main__":
    main()
