$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.12.2" -ForegroundColor Cyan
Write-Host " Devoluciones post-despacho y reproceso trazable" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta version dentro de la carpeta activa del ERP." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/7 Verificando PostgreSQL actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}

Write-Host "2/7 Creando backup integral antes de V21..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/7 Compilando backend y frontend v0.12.2 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup integral."}

Write-Host "4/7 Recreando servicios y aplicando Flyway V21..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=280 backend; Fail "El backend no alcanzo healthy." }

$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}

Write-Host "5/7 Verificando Flyway V21 y estructura de devoluciones..." -ForegroundColor Yellow
$v21=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='21' and success=true;" 2>$null)
if(($v21|Out-String).Trim() -ne '1'){Fail "Flyway V21 no aparece aplicada. No continue."}
$tables=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.tables where table_schema='public' and table_name in ('customer_returns','customer_return_lines','customer_return_events');" 2>$null)
if(($tables|Out-String).Trim() -ne '3'){Fail "No se encontraron las tablas de devoluciones."}
$cols=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.columns where table_schema='public' and table_name='production_orders' and column_name='source_return_id';" 2>$null)
if(($cols|Out-String).Trim() -ne '1'){Fail "Production Orders no quedo vinculada a devoluciones."}
$perms=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from permissions where code in ('RETURNS_READ','RETURNS_WRITE','RETURNS_DECIDE','RETURNS_REPROCESS');" 2>$null)
if(($perms|Out-String).Trim() -ne '4'){Fail "No se encontraron los permisos de devoluciones."}

Write-Host "6/7 Verificando version activa y frontend..." -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try {
    $build = Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5
    if($build.version -ne '0.12.2'){ Fail "El backend activo no es v0.12.2. Version detectada: $($build.version)" }
    Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green
} catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "7/7 Actualizacion v0.12.2 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Cierre sesion y vuelva a ingresar para cargar permisos de Devoluciones." -ForegroundColor White
Write-Host "- Use un despacho ENTREGADO de prueba y cree una devolucion parcial de uno de sus rollos." -ForegroundColor White
Write-Host "- Verifique que no permita devolver mas kg que los entregados ni duplicar kg ya devueltos." -ForegroundColor White
Write-Host "- Reciba la devolucion, revise Calidad y decida REPROCESO." -ForegroundColor White
Write-Host "- Cree la OP de reproceso, reserve insumos y complete Produccion > Calidad > Despacho." -ForegroundColor White
Write-Host "- El costeo de la OP de reproceso debe incluir costos de retorno sin duplicar la venta original." -ForegroundColor White
Write-Host "- Pruebe Excel/PDF de devoluciones y cierre el reclamo solo despues de entregar el reproceso." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
