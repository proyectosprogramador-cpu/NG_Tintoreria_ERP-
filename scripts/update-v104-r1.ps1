$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Hotfix v0.10.4 R1" -ForegroundColor Cyan
Write-Host " Dashboard HTTP 403 y permisos de Administrador" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie este hotfix dentro de la carpeta del ERP que ya utiliza." }
if (-not (Test-Path 'backend\src\main\resources\db\migration\V14__repair_dashboard_admin_permissions.sql')) { Fail "No se encontro la migracion V14 del hotfix." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/6 Verificando PostgreSQL actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady = $false
for ($i=0; $i -lt 30; $i++) {
  $health = (& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null)
  if ($health -eq 'healthy') { $dbReady = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $dbReady) { Fail "PostgreSQL no alcanzo el estado healthy." }

Write-Host "2/6 Creando backup obligatorio antes de V14..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/6 Compilando hotfix v0.10.4 R1..." -ForegroundColor Yellow
& docker compose build backend frontend
if ($LASTEXITCODE -ne 0) { Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup creado." }

Write-Host "4/6 Aplicando V14 y reiniciando servicios..." -ForegroundColor Yellow
& docker compose up -d
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar el hotfix. Ejecute VER_ESTADO_WINDOWS.bat y conserve el backup." }

$backendReady=$false
for($i=0;$i -lt 60;$i++){
  $health = (& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null)
  if($health -eq 'healthy'){$backendReady=$true;break}
  Start-Sleep -Seconds 2
}
if(-not $backendReady){
  Write-Host "`nUltimos logs del backend:" -ForegroundColor Yellow
  & docker compose logs --tail=140 backend
  Fail "El backend no alcanzo healthy. Revise el error anterior."
}

Write-Host "5/6 Verificando Flyway V14 y permisos..." -ForegroundColor Yellow
$dbUser='ngerp'; $dbName='ng_tintoreria'
$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
if($cfg['POSTGRES_USER']){$dbUser=$cfg['POSTGRES_USER']};if($cfg['POSTGRES_DB']){$dbName=$cfg['POSTGRES_DB']}
$v14 = (& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='14' and success=true;" 2>$null)
if(($v14 | Out-String).Trim() -ne '1'){ Fail "Flyway V14 no aparece como aplicada correctamente." }
$adminDash = (& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from role_permissions rp join roles r on r.id=rp.role_id join permissions p on p.id=rp.permission_id where r.code='ADMIN' and p.code='DASHBOARD_READ';" 2>$null)
if([int](($adminDash | Out-String).Trim()) -lt 1){ Fail "ADMIN aun no tiene DASHBOARD_READ despues de V14." }

$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio a tiempo. Ejecute VER_ESTADO_WINDOWS.bat."}

Write-Host "6/6 Hotfix v0.10.4 R1 aplicado correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nCierre sesion, vuelva a ingresar y abra Inicio." -ForegroundColor Yellow
Write-Host "El Dashboard ya no debe mostrar HTTP 403 para el Administrador." -ForegroundColor Cyan
Start-Process $url
Read-Host "Presione Enter para cerrar"
