$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Hotfix v0.13.1-R1" -ForegroundColor Cyan
Write-Host " Correccion integral de PDF / impresion en navegador" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie el hotfix dentro de la carpeta ACTIVA del ERP; no reemplace su .env." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

$cfg=@{}; Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
$url="http://localhost:$port"

Write-Host "1/6 Verificando ERP actual y PostgreSQL..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}
$current=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if(-not $current -or [int]$current -lt 25){Fail "Se esperaba Flyway V25 o superior. Version detectada: $current"}
Write-Host "Flyway V$current detectado. No se aplicaran migraciones en este hotfix." -ForegroundColor Green

Write-Host "2/6 Creando backup integral VALIDADO antes del hotfix..." -ForegroundColor Yellow
try { $pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1; Write-Host "Backup previo: $pre" -ForegroundColor Green } catch { Fail $_.Exception.Message }

Write-Host "3/6 Compilando backend y frontend v0.13.1-R1 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. La base no fue modificada; conserve el backup previo."}

Write-Host "4/6 Recreando servicios (sin cambios de base de datos)..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=300 backend; Fail "El backend no alcanzo healthy." }

Write-Host "5/6 Verificando version y frontend..." -ForegroundColor Yellow
$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try { $build=Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5; if($build.version -ne '0.13.1-R1'){Fail "El backend activo no es v0.13.1-R1. Version detectada: $($build.version)"}; Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green } catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "6/6 Confirmando integridad de Flyway y etapa de despliegue..." -ForegroundColor Yellow
$after=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if($after -ne $current){Fail "La version Flyway cambio inesperadamente de V$current a V$after."}
$stage=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select deployment_stage from system_environment_config where id=1;" 2>$null | Out-String).Trim()
Write-Host "Flyway conservado: V$after · Etapa conservada: $stage" -ForegroundColor Green

Write-Host "`nHOTFIX v0.13.1-R1 COMPLETADO CORRECTAMENTE." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Recepcion de telas: PDF / Imprimir acta debe mostrar el acta, no pagina en blanco." -ForegroundColor White
Write-Host "- Recepcion de telas: Imprimir etiquetas QR debe mostrar solo las etiquetas." -ForegroundColor White
Write-Host "- Produccion: Imprimir hoja viajera." -ForegroundColor White
Write-Host "- Laboratorio: PDF / Imprimir ficha." -ForegroundColor White
Write-Host "- Calidad, Despacho y Devoluciones: abrir detalle y probar Imprimir." -ForegroundColor White
Write-Host "- Estado del sistema: Imprimir debe mostrar la pagina sin menu lateral." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
