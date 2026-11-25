"""PyInstaller entry point for the portable 课业日程 executable."""

import sys


if __name__ == "__main__":
    from homework_scan.__main__ import main

    if len(sys.argv) == 1:
        sys.argv.append("gui")
    raise SystemExit(main())
