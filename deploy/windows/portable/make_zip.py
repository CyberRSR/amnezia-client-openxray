"""Build the portable Windows archive that carries the WWG service scripts.

    python deploy/windows/portable/make_zip.py --install-dir install --root AmneziaVPN-WWG-v0.7.2-win64 --out build/AmneziaVPN-WWG-v0.7.2-win64.zip
"""

import argparse
import hashlib
import os
import zipfile

SCRIPTS = ("install-service.cmd", "uninstall-service.cmd", "README-Windows.txt")

# MSI plumbing: the portable package registers the service through its own script,
# and post_uninstall.cmd would strip the service registration of any Amnezia build.
EXCLUDED = {"post_install.cmd", "post_uninstall.cmd"}


def arcpath(root, relative):
    return "/".join((root, *relative.split(os.sep)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-dir", required=True)
    parser.add_argument("--root", required=True, help="top level folder inside the archive")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    install_dir = os.path.abspath(args.install_dir)
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)

    digest = hashlib.sha256()
    with open(out, "wb") as raw, zipfile.ZipFile(raw, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for dirpath, _, filenames in os.walk(install_dir):
            for name in sorted(filenames):
                source = os.path.join(dirpath, name)
                relative = os.path.relpath(source, install_dir)
                if name in EXCLUDED:
                    continue
                archive.write(source, arcpath(args.root, relative))
        for name in SCRIPTS:
            archive.write(os.path.join(here, name), arcpath(args.root, name))

    with open(out, "rb") as written:
        for chunk in iter(lambda: written.read(1 << 20), b""):
            digest.update(chunk)

    print(f"{out}\n  {os.path.getsize(out):,} bytes\n  sha256 {digest.hexdigest()}")


if __name__ == "__main__":
    main()
