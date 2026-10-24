$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Hotfix v0.10.4 R2" -ForegroundColor Cyan
Write-Host " Dashboard HTTP 403 - autorizacion reforzada" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie este hotfix dentro de la carpeta activa del ERP." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/6 Verificando PostgreSQL actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}

Write-Host "2/6 Creando backup obligatorio..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/6 Reconstruyendo backend y frontend SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados."}

Write-Host "4/6 Recreando backend y frontend con las imagenes R2..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 60;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=160 backend; Fail "El backend no alcanzo healthy." }

Write-Host "5/6 Verificando Flyway V14 y servicios..." -ForegroundColor Yellow
$dbUser='ngerp';$dbName='ng_tintoreria';$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
if($cfg['POSTGRES_USER']){$dbUser=$cfg['POSTGRES_USER']};if($cfg['POSTGRES_DB']){$dbName=$cfg['POSTGRES_DB']}
$v14=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='14' and success=true;" 2>$null)
if(($v14|Out-String).Trim() -ne '1'){Fail "Flyway V14 no aparece aplicada."}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}

Write-Host "6/6 Hotfix v0.10.4 R2 aplicado correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nIMPORTANTE:" -ForegroundColor Yellow
Write-Host "1. Cierre sesion." -ForegroundColor White
Write-Host "2. Presione Ctrl+F5 en el navegador para vaciar la pagina en cache." -ForegroundColor White
Write-Host "3. Ingrese nuevamente y abra Inicio." -ForegroundColor White
Write-Host "Debe verse 'v0.10.4 R2' en la barra lateral." -ForegroundColor Cyan
Start-Process $url
Read-Host "Presione Enter para cerrar"
