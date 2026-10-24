$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
Write-Host '=== DIAGNOSTICO MANTENIMIENTO v0.12.1 ===' -ForegroundColor Cyan
& docker compose ps
$cfg=@{}; if(Test-Path '.env'){Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
Write-Host "`n--- Backend build ---" -ForegroundColor Yellow
try{Invoke-RestMethod -Uri "http://localhost:$port/api/system/build" -TimeoutSec 5|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway V20 ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history where version='20';"
Write-Host "`n--- Estado maquinas ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select code,name,operational_status,current_meter_hours,maintenance_interval_hours,maintenance_interval_days,next_maintenance_at,next_maintenance_meter_hours from machines order by code;"
Write-Host "`n--- OT abiertas ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select maintenance_number,status,maintenance_type,priority,scheduled_start,scheduled_end from maintenance_work_orders where status in ('PLANNED','IN_PROGRESS') order by scheduled_start;"
Write-Host "`n--- Permisos ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select code from permissions where code like 'MAINTENANCE_%' order by code;"
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
& docker compose logs backend --tail=160
Read-Host 'Presione Enter para cerrar'
