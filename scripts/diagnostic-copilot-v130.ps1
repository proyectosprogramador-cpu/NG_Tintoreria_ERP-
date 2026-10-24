$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path);Set-Location $root
$cfg=@{};if(Test-Path '.env'){Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
Write-Host '=== DIAGNOSTICO NG COPILOTO v0.13.0 ===' -ForegroundColor Cyan
& docker compose ps
Write-Host "`n--- Flyway ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history order by installed_rank desc limit 3;"
Write-Host "`n--- Permiso y recordatorios ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select p.code,count(rp.role_id) roles from permissions p left join role_permissions rp on rp.permission_id=p.id where p.code='COPILOT_USE' group by p.code; select count(*) reminders from copilot_reminders;"
Write-Host "`n--- Backend build ---" -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};try{Invoke-RestMethod -Uri "http://localhost:$port/api/system/build" -TimeoutSec 5|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Read-Host 'Presione Enter para cerrar'
