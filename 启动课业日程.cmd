@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\pythonw.exe" (
    uv sync --locked
    if errorlevel 1 (
        pause
        exit /b 1
    )
)
start "" ".venv\Scripts\pythonw.exe" -m homework_scan gui
