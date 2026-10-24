$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Hotfix seguro v0.13.0 R1" -ForegroundColor Cyan
Write-Host " Copiloto + Dashboard trend/alerts" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie este hotfix dentro de la carpeta ACTIVA del ERP v0.13.0; no reemplace su .env." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

$cfg=@{}; Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
$url="http://localhost:$port"

Write-Host "1/7 Verificando PostgreSQL y Flyway V24..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}
$current=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select coalesce(max(version::int),0) from flyway_schema_history where success=true;" 2>$null | Out-String).Trim()
if(-not $current -or [int]$current -lt 24){Fail "Se esperaba Flyway V24 o superior. Version detectada: $current"}
Write-Host "Esquema detectado: Flyway V$current" -ForegroundColor Green

Write-Host "2/7 Creando backup integral validado antes del hotfix..." -ForegroundColor Yellow
try { $pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1; Write-Host "Backup previo: $pre" -ForegroundColor Green } catch { Fail $_.Exception.Message }

Write-Host "3/7 Compilando backend y frontend v0.13.0 R1 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. La base no fue modificada; conserve el backup previo."}

Write-Host "4/7 Recreando servicios..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=300 backend; Fail "El backend no alcanzo healthy." }

Write-Host "5/7 Validando SQL corregido del Dashboard..." -ForegroundColor Yellow
$trendSql="select actual_end::date AS trend_day,coalesce(sum(actual_output_weight_kg),0) produced_kg from production_orders where actual_end::date between current_date-interval '30 days' and current_date group by actual_end::date order by actual_end::date;"
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $trendSql *> $null
if($LASTEXITCODE -ne 0){Fail "La consulta trend corregida no paso validacion."}
$alertSql="select case when po.status='QUALITY_REJECTED' then 'CRITICAL' else 'HIGH' end severity,po.production_number reference,po.updated_at event_at from production_orders po join clients c on c.id=po.client_id where po.status in ('REPROCESS_REQUIRED','QUALITY_REJECTED') order by po.updated_at desc limit 5;"
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $alertSql *> $null
if($LASTEXITCODE -ne 0){Fail "La consulta alerts corregida no paso validacion."}
Write-Host "Trend y alerts: SQL valido." -ForegroundColor Green

Write-Host "6/7 Verificando version activa y frontend..." -ForegroundColor Yellow
$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try { $build=Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5; if($build.version -ne '0.13.0-R1'){Fail "El backend activo no es v0.13.0-R1. Version detectada: $($build.version)"}; Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green } catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "7/7 Confirmando etapa de despliegue sin alterarla..." -ForegroundColor Yellow
$stage=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select deployment_stage from system_environment_config where id=1;" 2>$null | Out-String).Trim()
Write-Host "Etapa conservada: $stage" -ForegroundColor Green

Write-Host "`nHOTFIX v0.13.0 R1 COMPLETADO CORRECTAMENTE." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Inicio: el Dashboard ya no debe mostrar 'trend, alerts' como secciones fallidas." -ForegroundColor White
Write-Host "- Copiloto: pregunte '¿Cual fue el costo y margen de OP-000001?'" -ForegroundColor White
Write-Host "- Copiloto en Produccion: pregunte '¿Que puedo hacer desde este modulo?'" -ForegroundColor White
Write-Host "- Inventario: un insumo con 0 debe mostrarse como SIN STOCK; validar singular/plural." -ForegroundColor White
Write-Host "- OP: material CONSUMED debe mostrarse como Consumido." -ForegroundColor White
Write-Host "- Verifique que los accesos rapidos del Copiloto ya no generen scroll horizontal." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
