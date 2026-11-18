#!/usr/bin/env python3
"""Keep KMP source variants usable after JitPack removes artifact classifiers."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys


def main():
    group, version = sys.argv[1:]
    repository = Path(os.path.expanduser("~/.m2/repository")) / group.replace(".", "/")
    for root in ("kmp-updater", "kmp-updater-compose"):
        for suffix in ("", "-android", "-jvm"):
            module = root + suffix
            directory = repository / module / version
            artifact_base = "{}-{}".format(module, version)
            sources = directory / (artifact_base + "-sources.jar")
            archive_name = artifact_base + ".zip"
            metadata_path = directory / (artifact_base + ".module")
            with metadata_path.open(encoding="utf-8") as metadata_file:
                metadata = json.load(metadata_file)
            with sources.open("rb") as sources_file:
                source_bytes = sources_file.read()
            source_variants = 0
            for variant in metadata["variants"]:
                if variant.get("attributes", {}).get("org.gradle.docstype") != "sources":
                    continue
                for artifact in variant.get("files", []):
                    if artifact["size"] != len(source_bytes) or artifact["sha256"] != hashlib.sha256(source_bytes).hexdigest():
                        raise ValueError("Source artifact does not match metadata: {}".format(module))
                    artifact["name"] = archive_name
                    artifact["url"] = archive_name
                    source_variants += 1
            if source_variants != 1:
                raise ValueError("Expected one source archive for {}, got {}".format(module, source_variants))
            # Retain the standard sources JAR for Maven and older IDEs.
            # Legacy JitPack images run a Python without os.PathLike support.
            shutil.copyfile(str(sources), str(directory / archive_name))
            with metadata_path.open("w", encoding="utf-8") as metadata_file:
                json.dump(metadata, metadata_file, indent=2)
                metadata_file.write("\n")
            print("Prepared JitPack sources: {}:{}".format(module, version))


if __name__ == "__main__":
    main()
