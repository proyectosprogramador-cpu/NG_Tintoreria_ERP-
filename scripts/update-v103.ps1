$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.10.3" -ForegroundColor Cyan
Write-Host " Costeo industrial, costo real por OP y margen" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta actualizacion dentro de la carpeta del ERP que ya utiliza." }
if (-not (Test-Path 'backend\src\main\resources\db\migration\V12__industrial_costing.sql')) { Fail "No se encontro la migracion V12 del modulo de Costeo industrial." }
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

Write-Host "2/6 Creando backup obligatorio antes de V12..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/6 Compilando backend y frontend v0.10.3..." -ForegroundColor Yellow
& docker compose build backend frontend
if ($LASTEXITCODE -ne 0) { Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup creado." }

Write-Host "4/6 Aplicando migracion V12 e iniciando servicios..." -ForegroundColor Yellow
& docker compose up -d
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar v0.10.3. Ejecute VER_ESTADO_WINDOWS.bat y conserve el backup." }

$backendReady=$false
for($i=0;$i -lt 60;$i++){
  $health = (& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null)
  if($health -eq 'healthy'){$backendReady=$true;break}
  Start-Sleep -Seconds 2
}
if(-not $backendReady){
  Write-Host "`nUltimos logs del backend:" -ForegroundColor Yellow
  & docker compose logs --tail=120 backend
  Fail "El backend no alcanzo healthy. Revise el error anterior."
}

Write-Host "5/6 Verificando Flyway V12..." -ForegroundColor Yellow
$dbUser='ngerp'; $dbName='ng_tintoreria'
$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
if($cfg['POSTGRES_USER']){$dbUser=$cfg['POSTGRES_USER']};if($cfg['POSTGRES_DB']){$dbName=$cfg['POSTGRES_DB']}
$v12 = (& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='12' and success=true;" 2>$null)
if(($v12 | Out-String).Trim() -ne '1'){ Fail "Flyway V12 no aparece como aplicada correctamente." }

$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio a tiempo. Ejecute VER_ESTADO_WINDOWS.bat."}

Write-Host "6/6 Actualizacion v0.10.3 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nCierre sesion e ingrese nuevamente para cargar los permisos de Costeo." -ForegroundColor Yellow
Write-Host "Configure primero las tarifas de costeo y calcule una OP de prueba antes de usar los márgenes para decisiones reales." -ForegroundColor Cyan
Start-Process $url
Read-Host "Presione Enter para cerrar"
