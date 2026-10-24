$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.13.1" -ForegroundColor Cyan
Write-Host " NG Copiloto Proactivo: alertas, briefing y acciones confirmables" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta actualizacion dentro de la carpeta ACTIVA del ERP; no reemplace su .env." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

$cfg=@{}; Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
$url="http://localhost:$port"

Write-Host "1/8 Verificando PostgreSQL y Flyway V24+..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}
$current=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if(-not $current -or [int]$current -lt 24){Fail "Se esperaba Flyway V24 o superior. Version detectada: $current"}
Write-Host "Esquema detectado: Flyway V$current" -ForegroundColor Green

Write-Host "2/8 Creando backup integral VALIDADO antes de V25..." -ForegroundColor Yellow
try { $pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1; Write-Host "Backup previo: $pre" -ForegroundColor Green } catch { Fail $_.Exception.Message }

Write-Host "3/8 Compilando backend y frontend v0.13.1 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. La base no fue modificada; conserve el backup previo."}

Write-Host "4/8 Recreando servicios y aplicando Flyway V25..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=300 backend; Fail "El backend no alcanzo healthy." }

Write-Host "5/8 Verificando Flyway V25 y tablas del Copiloto Proactivo..." -ForegroundColor Yellow
$after=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if([int]$after -lt 25){Fail "Flyway V25 no fue aplicada. Version detectada: $after"}
$table=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select to_regclass('public.copilot_alert_user_state');" 2>$null | Out-String).Trim()
if($table -ne 'copilot_alert_user_state'){Fail "No existe copilot_alert_user_state despues de V25."}
Write-Host "Flyway V$after + estado de alertas: OK" -ForegroundColor Green

Write-Host "6/8 Validando consultas base de alertas..." -ForegroundColor Yellow
$stockSql="with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id), reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id) select count(*) from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock;"
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $stockSql *> $null
if($LASTEXITCODE -ne 0){Fail "La consulta proactiva de inventario no paso validacion."}
$prodSql="select count(*) from production_orders where status in ('PLANNED','IN_PROCESS','PAUSED') and (status='PAUSED' or scheduled_end<now());"
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $prodSql *> $null
if($LASTEXITCODE -ne 0){Fail "La consulta proactiva de produccion no paso validacion."}
Write-Host "Consultas proactivas base: OK" -ForegroundColor Green

Write-Host "7/8 Verificando version activa y frontend..." -ForegroundColor Yellow
$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try { $build=Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5; if($build.version -ne '0.13.1'){Fail "El backend activo no es v0.13.1. Version detectada: $($build.version)"}; Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green } catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "8/8 Confirmando etapa de despliegue sin alterarla..." -ForegroundColor Yellow
$stage=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select deployment_stage from system_environment_config where id=1;" 2>$null | Out-String).Trim()
Write-Host "Etapa conservada: $stage" -ForegroundColor Green

Write-Host "`nACTUALIZACION v0.13.1 COMPLETADA CORRECTAMENTE." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Cierre sesion y vuelva a ingresar." -ForegroundColor White
Write-Host "- Abra NG Copiloto: debe generar un Briefing de turno al abrir por primera vez." -ForegroundColor White
Write-Host "- Verifique el indicador de alertas en el boton flotante y el icono de campana." -ForegroundColor White
Write-Host "- Con inventario sin stock, confirme que aparezca alerta Critica y opciones Ver / Recordarme / 1 h / Descartar." -ForegroundColor White
Write-Host "- Active voz y compruebe que una alerta nueva pueda anunciarse por voz." -ForegroundColor White
Write-Host "- Si una OP planificada tiene faltantes y su rol puede comprar, pruebe Preparar solicitud de compra y CONFIRME la accion." -ForegroundColor White
Write-Host "- Confirme que roles sin permisos solo reciban alertas de modulos autorizados." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
