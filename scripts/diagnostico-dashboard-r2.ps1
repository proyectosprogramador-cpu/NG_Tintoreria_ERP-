$ErrorActionPreference='Continue'
Set-Location (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
Write-Host "=== DIAGNOSTICO DASHBOARD v0.10.4 R2 ===" -ForegroundColor Cyan
docker compose ps
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
docker compose logs --tail=120 backend
Write-Host "`n--- Flyway V13/V14 ---" -ForegroundColor Yellow
$cfg=@{};Get-Content '.env'|%{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}
$u=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$d=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
docker exec ng_tintoreria_db psql -U $u -d $d -c "select installed_rank,version,description,success from flyway_schema_history where version in ('13','14') order by installed_rank;"
Write-Host "`n--- Usuario ADMIN y DASHBOARD_READ ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $u -d $d -c "select au.email,r.code role,p.code permission from app_users au join user_roles ur on ur.user_id=au.id join roles r on r.id=ur.role_id left join role_permissions rp on rp.role_id=r.id left join permissions p on p.id=rp.permission_id and p.code='DASHBOARD_READ' where r.code='ADMIN';"
Read-Host "Presione Enter para cerrar"
