@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title NG Tintoreria ERP - Inicio seguro

echo =================================================
echo  NG Tintoreria ERP - INICIO SEGURO
echo  No instala hotfixes ni reconstruye imagenes
echo =================================================
echo.

if not exist ".env" (
  echo ERROR: No se encontro .env junto a este archivo.
  echo Coloque este BAT en la carpeta raiz del ERP.
  pause
  exit /b 1
)

where docker >nul 2>&1
if errorlevel 1 (
  echo ERROR: Docker no esta disponible en PATH.
  pause
  exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
  echo ERROR: Docker Desktop no esta iniciado.
  echo Abra Docker Desktop y vuelva a intentar.
  pause
  exit /b 1
)

echo Iniciando PostgreSQL, backend y frontend...
docker compose up -d --no-recreate db backend frontend
if errorlevel 1 (
  echo ERROR: Docker Compose no pudo iniciar los servicios.
  docker compose ps -a
  pause
  exit /b 1
)

echo.
echo Estado de los servicios:
docker compose ps

echo.
set "ERP_PORT=8080"
for /f "usebackq tokens=1,* delims==" %%A in (".env") do if /I "%%A"=="ERP_PORT" set "ERP_PORT=%%B"
echo Abriendo http://localhost:%ERP_PORT%
start "" "http://localhost:%ERP_PORT%"

echo.
echo Si backend o db figuran como starting, espere unos segundos
echo y refresque el navegador. Este BAT NO aplica actualizaciones.
pause
endlocal
