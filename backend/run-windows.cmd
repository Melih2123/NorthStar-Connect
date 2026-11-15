@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\activate.bat" (
    echo [NorthStar] Creating Python virtual environment...
    py -m venv .venv
)
call ".venv\Scripts\activate.bat"
python -c "import fastapi, uvicorn, multipart" >nul 2>&1
if errorlevel 1 (
    echo [NorthStar] Required backend packages are missing; installing requirements...
    python -m pip install --disable-pip-version-check --index-url https://pypi.org/simple -r requirements.txt
    if errorlevel 1 (
        echo [ERROR] Dependency installation failed. Check your network or pip index configuration.
        pause
        exit /b 1
    )
)
echo [NorthStar] Starting FastAPI on http://127.0.0.1:8000
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
pause
