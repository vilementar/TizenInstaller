@echo off
title Tizen Watch Installer (Standalone)
cd /d "%~dp0"
python installer.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Wystapil blad podczas uruchamiania instalatora.
    pause
)
