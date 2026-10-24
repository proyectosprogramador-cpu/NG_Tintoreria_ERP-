@echo off
cd /d "%~dp0"
docker compose ps
echo.
echo Ultimos mensajes del backend:
docker compose logs --tail=40 backend
pause
