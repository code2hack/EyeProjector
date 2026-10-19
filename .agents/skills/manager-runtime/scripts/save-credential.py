#!/usr/bin/env python3
"""Owner-requested credential retention through a direct, no-echo TTY."""
import argparse
import getpass
import json
import os
from pathlib import Path
import sys
import tempfile


def save_credential(destination, repo_root, value):
    destination, repo_root = Path(destination).resolve(), Path(repo_root).resolve()
    if destination.is_relative_to(repo_root):
        raise ValueError("Choose a credential file outside the repository")
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if destination.parent.stat().st_mode & 0o077:
        raise ValueError("Use a dedicated private directory with mode 0700")
    fd, temporary = tempfile.mkstemp(prefix=".credential-", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(value.encode("utf-8"))
            output.flush()
            os.fsync(output.fileno())
        os.link(temporary, destination)
        directory = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        os.unlink(temporary)
    return str(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", required=True)
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--owner-request", required=True)
    args = parser.parse_args()
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise ValueError("Run in the Owner's direct tmux TTY")
    first = getpass.getpass("Credential: ")
    second = getpass.getpass("Repeat credential: ")
    if first != second:
        raise ValueError("Entries differ; no credential was saved")
    destination = save_credential(args.file, args.repo_root, first)
    print(json.dumps({"path": destination, "ownerRequest": args.owner_request}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, EOFError, KeyboardInterrupt):
        print("Credential capture did not complete; no value is displayed.", file=sys.stderr)
        sys.exit(1)
