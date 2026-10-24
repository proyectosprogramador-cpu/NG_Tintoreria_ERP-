$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.13.0" -ForegroundColor Cyan
Write-Host " NG Copiloto Industrial, voz y recordatorios" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta actualizacion dentro de la carpeta ACTIVA del ERP v0.12.4; no reemplace su .env." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/8 Verificando PostgreSQL productivo actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}
$cfg=@{}; Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$current=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if(-not $current -or [int]$current -lt 23){Fail "Se esperaba una base v0.12.4 / Flyway V23 o superior. Version detectada: $current"}
Write-Host "Esquema detectado: Flyway V$current" -ForegroundColor Green

Write-Host "2/8 Creando backup integral VALIDADO antes de V24..." -ForegroundColor Yellow
try { $pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1; Write-Host "Backup previo: $pre" -ForegroundColor Green } catch { Fail $_.Exception.Message }

Write-Host "3/8 Compilando backend y frontend v0.13.0 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados por Flyway; conserve el backup previo."}

Write-Host "4/8 Recreando servicios y aplicando Flyway V24..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=300 backend; Fail "El backend no alcanzo healthy." }

Write-Host "5/8 Verificando migracion, permisos y recordatorios..." -ForegroundColor Yellow
$v24=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='24' and success=true;" 2>$null | Out-String).Trim()
if($v24 -ne '1'){Fail "Flyway V24 no aparece aplicada."}
$table=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.tables where table_schema='public' and table_name='copilot_reminders';" 2>$null | Out-String).Trim()
if($table -ne '1'){Fail "No se encontro copilot_reminders."}
$perm=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from permissions where code='COPILOT_USE';" 2>$null | Out-String).Trim()
if($perm -ne '1'){Fail "No se encontro el permiso COPILOT_USE."}
$roles=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(distinct r.id) from role_permissions rp join roles r on r.id=rp.role_id join permissions p on p.id=rp.permission_id where p.code='COPILOT_USE';" 2>$null | Out-String).Trim()
Write-Host "COPILOT_USE asignado a $roles rol(es)." -ForegroundColor Green

Write-Host "6/8 Verificando version activa y frontend..." -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}; $url="http://localhost:$port"; $ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try { $build=Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5; if($build.version -ne '0.13.0'){Fail "El backend activo no es v0.13.0. Version detectada: $($build.version)"}; Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green } catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "7/8 Verificando que Produccion no haya sido degradada..." -ForegroundColor Yellow
$stage=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select deployment_stage from system_environment_config where id=1;" 2>$null | Out-String).Trim()
Write-Host "Etapa conservada: $stage" -ForegroundColor Green

Write-Host "8/8 Actualizacion v0.13.0 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Cierre sesion y vuelva a ingresar para renovar el JWT con COPILOT_USE." -ForegroundColor White
Write-Host "- Abra el boton flotante NG Copiloto y pulse Resumen de hoy." -ForegroundColor White
Write-Host "- Consulte una OP real (ej.: OP-000001) y verifique que respete permisos." -ForegroundColor White
Write-Host "- Pruebe microfono si Chrome/Edge lo permite y active voz con el boton de altavoz." -ForegroundColor White
Write-Host "- Cree un recordatorio a 2-3 minutos y confirme el aviso dentro del ERP." -ForegroundColor White
Write-Host "- Usuarios sin COSTING_READ no deben recibir costos ni margen." -ForegroundColor White
Write-Host "- La IA generativa externa permanece DESACTIVADA salvo configuracion explicita en .env." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
