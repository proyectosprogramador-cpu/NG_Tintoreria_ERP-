$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
Write-Host '=== DIAGNOSTICO ESTADO DEL SISTEMA v0.12.4 ===' -ForegroundColor Cyan
docker compose ps
$cfg=@{}; if(Test-Path '.env'){Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
Write-Host "`n--- Backend build ---" -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};try{Invoke-RestMethod "http://localhost:$port/api/system/build"|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway V23 ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history where version>='20' order by installed_rank;"
Write-Host "`n--- Configuracion de despliegue ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select * from system_environment_config;"
Write-Host "`n--- Ultimos preflight ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select overall_status,blocker_count,warning_count,run_by,created_at from system_preflight_runs order by created_at desc limit 10;"
Write-Host "`n--- Backups ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select filename,status,created_at,verified_at,restored_at from system_backup_history order by created_at desc limit 10;"
Write-Host "`n--- Ultimos eventos fallidos ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select created_at,actor,module,action,request_path,left(coalesce(detail,''),120) detail from audit_events where success=false order by created_at desc limit 15;"
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
docker compose logs backend --tail=120
