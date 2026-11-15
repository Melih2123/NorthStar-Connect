@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\activate.bat" (
    echo Run run-windows.cmd once to create the virtual environment.
    pause
    exit /b 1
)
call ".venv\Scripts\activate.bat"
python -m pytest -q
pause
