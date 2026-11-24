$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }
function Port-In-Use([int]$port) {
  try {
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    return ($listeners | Where-Object { $_.Port -eq $port }).Count -gt 0
  } catch { return $false }
}

Write-Host "===============================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP v0.13.0 - Produccion + NG Copiloto" -ForegroundColor Cyan
Write-Host "===============================================" -ForegroundColor Cyan

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Docker Desktop esta instalado pero el motor Docker no esta iniciado." }

$envFile = Join-Path $root '.env'
$credentialsFile = Join-Path $root 'CREDENCIALES_INICIALES.txt'

# Reutiliza el puerto si el frontend ya esta en ejecucion.
$running = (& docker compose ps --services --status running 2>$null) -join ','
if ($running -match 'frontend') {
  $line = Get-Content $envFile -ErrorAction SilentlyContinue | Where-Object { $_ -match '^ERP_PORT=' } | Select-Object -First 1
  $port = if ($line) { [int]($line -replace '^ERP_PORT=','') } else { 8080 }
  $url = "http://localhost:$port"
  Write-Host "La v0.13.0 ya esta ejecutandose en $url" -ForegroundColor Green
  Start-Process $url
  exit 0
}

$port = 8080
while ($port -le 8099 -and (Port-In-Use $port)) { $port++ }
if ($port -gt 8099) { Fail "No hay puertos libres entre 8080 y 8099." }

if (-not (Test-Path $envFile)) {
  $dbPassword = ([guid]::NewGuid().ToString('N') + 'Aa9!').Substring(0,28)
  $jwtSecret = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
  $adminPassword = 'NG-' + ([guid]::NewGuid().ToString('N').Substring(0,12)) + '!9a'
  @"
ERP_PORT=$port
POSTGRES_DB=ng_tintoreria
POSTGRES_USER=ngerp
POSTGRES_PASSWORD=$dbPassword
ERP_JWT_SECRET=$jwtSecret
ERP_JWT_EXPIRATION_MINUTES=480
ERP_MAX_LOGIN_ATTEMPTS=5
ERP_LOGIN_LOCK_MINUTES=15
ERP_BOOTSTRAP_ADMIN_EMAIL=admin@ng.local
ERP_BOOTSTRAP_ADMIN_PASSWORD=$adminPassword
ERP_BOOTSTRAP_ADMIN_NAME=Neul Guzman
SPRING_PROFILES_ACTIVE=demo
"@ | Set-Content -Encoding ASCII $envFile
  @"
NG TINTORERIA ERP v0.13.0 - CREDENCIALES INICIALES
=================================================
Usuario: admin@ng.local
Contrasena: $adminPassword

IMPORTANTE:
1. Guarde este archivo en un lugar seguro.
2. Cambie la contrasena del administrador antes del piloto real.
3. No comparta el archivo .env ni estas credenciales por mensajeria.
"@ | Set-Content -Encoding UTF8 $credentialsFile
  Write-Host "Se generaron credenciales iniciales seguras en CREDENCIALES_INICIALES.txt" -ForegroundColor Yellow
} else {
  $content = Get-Content $envFile
  $content = $content | ForEach-Object { if ($_ -match '^ERP_PORT=') { "ERP_PORT=$port" } else { $_ } }
  $content | Set-Content -Encoding ASCII $envFile
}

Write-Host "Puerto seleccionado: $port" -ForegroundColor Yellow
Write-Host "Construyendo/iniciando PostgreSQL + Backend + Frontend..." -ForegroundColor Cyan
Write-Host "La primera instalacion necesita Internet para descargar imagenes y dependencias. Luego puede operar en LAN sin Internet." -ForegroundColor DarkYellow
& docker compose up -d --build
if ($LASTEXITCODE -ne 0) { Fail "Docker Compose no pudo iniciar el ERP. Revise los mensajes anteriores." }

$url = "http://localhost:$port"
$ready = $false
for ($i=0; $i -lt 90; $i++) {
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2
    if ($r.StatusCode -eq 200) { $ready = $true; break }
  } catch {}
  Start-Sleep -Seconds 2
}
if (-not $ready) { Fail "El ERP no respondio a tiempo. Ejecute VER_ESTADO_WINDOWS.bat para revisar los contenedores." }

$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*' } | Select-Object -ExpandProperty IPAddress -Unique
Write-Host "`nERP LISTO" -ForegroundColor Green
Write-Host "PC servidor: $url" -ForegroundColor Green
foreach ($ip in $ips) { Write-Host "Otras PC/celulares en la misma red: http://${ip}:$port" -ForegroundColor Green }
Write-Host "`nSi Internet se cae pero la LAN sigue activa, estos modulos productivos continuan disponibles." -ForegroundColor Cyan
Start-Process $url
