$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.13.2" -ForegroundColor Cyan
Write-Host " Comunicaciones inteligentes: Email + WhatsApp webhook" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta actualizacion dentro de la carpeta ACTIVA del ERP; no reemplace su .env." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

$cfg=@{}; Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
$url="http://localhost:$port"

Write-Host "1/8 Verificando PostgreSQL y Flyway V25+..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}
$current=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if(-not $current -or [int]$current -lt 25){Fail "Se esperaba Flyway V25 o superior. Version detectada: $current"}
Write-Host "Esquema detectado: Flyway V$current" -ForegroundColor Green

Write-Host "2/8 Creando backup integral VALIDADO antes de V26..." -ForegroundColor Yellow
try { $pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1; Write-Host "Backup previo: $pre" -ForegroundColor Green } catch { Fail $_.Exception.Message }

Write-Host "3/8 Compilando backend y frontend v0.13.2 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. La base no fue modificada; conserve el backup previo."}

Write-Host "4/8 Recreando servicios y aplicando Flyway V26..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=300 backend; Fail "El backend no alcanzo healthy." }

Write-Host "5/8 Verificando Flyway V26 y estructuras de comunicaciones..." -ForegroundColor Yellow
$after=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if([int]$after -lt 26){Fail "Flyway V26 no fue aplicada. Version detectada: $after"}
$tables=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.tables where table_schema='public' and table_name in ('copilot_notification_contacts','copilot_notification_rules','copilot_notification_outbox');" 2>$null | Out-String).Trim()
if($tables -ne '3'){Fail "No se encontraron las 3 tablas de comunicaciones despues de V26."}
$perms=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from permissions where code in ('NOTIFICATIONS_READ','NOTIFICATIONS_MANAGE');" 2>$null | Out-String).Trim()
if($perms -ne '2'){Fail "Permisos de comunicaciones incompletos."}
Write-Host "Flyway V$after + tablas + permisos: OK" -ForegroundColor Green

Write-Host "6/8 Validando reglas, destinatarios y modo seguro..." -ForegroundColor Yellow
$rules=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from copilot_notification_rules where enabled=true;" 2>$null | Out-String).Trim()
$contacts=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from copilot_notification_contacts where active=true;" 2>$null | Out-String).Trim()
$dry=if($cfg.ContainsKey('COPILOT_NOTIFY_DRY_RUN')){$cfg['COPILOT_NOTIFY_DRY_RUN']}else{'true'}
Write-Host "Reglas activas: $rules · Destinatarios: $contacts · DRY_RUN: $dry" -ForegroundColor Green
if($dry -ne 'false'){Write-Host "Modo SIMULACION seguro: no se enviaran mensajes externos hasta configurar proveedores y COPILOT_NOTIFY_DRY_RUN=false." -ForegroundColor DarkYellow}

Write-Host "7/8 Verificando version activa y frontend..." -ForegroundColor Yellow
$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try { $build=Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5; if($build.version -ne '0.13.2'){Fail "El backend activo no es v0.13.2. Version detectada: $($build.version)"}; Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green } catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "8/8 Confirmando etapa de despliegue sin alterarla..." -ForegroundColor Yellow
$stage=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select deployment_stage from system_environment_config where id=1;" 2>$null | Out-String).Trim()
Write-Host "Etapa conservada: $stage" -ForegroundColor Green

Write-Host "`nACTUALIZACION v0.13.2 COMPLETADA CORRECTAMENTE." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Cierre sesion y vuelva a ingresar para renovar permisos del JWT." -ForegroundColor White
Write-Host "- Abra Gestion > Comunicaciones IA." -ForegroundColor White
Write-Host "- Debe mostrar SIMULACION SEGURA, 6 reglas y al menos el destinatario Administrador." -ForegroundColor White
Write-Host "- Edite un destinatario, habilite Email o WhatsApp y use Probar. En DRY_RUN debe quedar SIMULATED." -ForegroundColor White
Write-Host "- Verifique el Historial de comunicaciones y Auditoria." -ForegroundColor White
Write-Host "- NO active envio real hasta configurar SMTP/webhook y realizar una prueba controlada." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
