$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Hotfix seguro v0.13.2-R1" -ForegroundColor Cyan
Write-Host " Health desacoplado de SMTP opcional + Comunicaciones IA" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie este hotfix dentro de la carpeta ACTIVA del ERP; no reemplace su .env." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

$cfg=@{}; Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
$url="http://localhost:$port"

Write-Host "1/7 Verificando PostgreSQL y Flyway V26..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}
$current=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if(-not $current -or [int]$current -lt 26){Fail "Se esperaba Flyway V26. Version detectada: $current"}
Write-Host "Esquema detectado: Flyway V$current" -ForegroundColor Green

Write-Host "2/7 Creando backup integral VALIDADO antes del hotfix..." -ForegroundColor Yellow
try { $pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1; Write-Host "Backup previo: $pre" -ForegroundColor Green } catch { Fail $_.Exception.Message }

Write-Host "3/7 Compilando backend/frontend v0.13.2-R1 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. No se realizaron cambios de esquema; conserve el backup previo."}

Write-Host "4/7 Recreando servicios con healthcheck corregido..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=220 backend; Fail "El backend no alcanzo healthy con /actuator/health/liveness." }
Write-Host "Backend Docker: HEALTHY" -ForegroundColor Green

Write-Host "5/7 Verificando health agregado y estructuras V26..." -ForegroundColor Yellow
try {
  $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get -TimeoutSec 5
  if($health.status -ne 'UP'){Fail "Actuator agregado no esta UP: $($health.status)"}
} catch { Fail "Actuator /health no respondio UP: $($_.Exception.Message)" }
$tables=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.tables where table_schema='public' and table_name in ('copilot_notification_contacts','copilot_notification_rules','copilot_notification_outbox');" 2>$null | Out-String).Trim()
if($tables -ne '3'){Fail "No se encontraron las 3 tablas de comunicaciones V26."}
Write-Host "Actuator UP + 3 tablas de comunicaciones: OK" -ForegroundColor Green

Write-Host "6/7 Verificando version activa y frontend..." -ForegroundColor Yellow
$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try { $build=Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5; if($build.version -ne '0.13.2-R1'){Fail "El backend activo no es v0.13.2-R1. Version detectada: $($build.version)"}; Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green } catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "7/7 Confirmando modo seguro de comunicaciones..." -ForegroundColor Yellow
$dry=if($cfg.ContainsKey('COPILOT_NOTIFY_DRY_RUN')){$cfg['COPILOT_NOTIFY_DRY_RUN']}else{'true'}
Write-Host "COPILOT_NOTIFY_DRY_RUN=$dry" -ForegroundColor Green
if($dry -ne 'false'){Write-Host "SIMULACION SEGURA activa: no salen mensajes reales." -ForegroundColor DarkYellow}

Write-Host "`nHOTFIX v0.13.2-R1 COMPLETADO CORRECTAMENTE." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Debe ver backend HEALTHY y frontend Up." -ForegroundColor White
Write-Host "- Cierre sesion y vuelva a ingresar." -ForegroundColor White
Write-Host "- Abra Gestion > Comunicaciones IA." -ForegroundColor White
Write-Host "- Verifique SIMULACION SEGURA y pruebe Email/WhatsApp: deben quedar SIMULATED." -ForegroundColor White
Write-Host "- No configure envio real todavia." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
