$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path);Set-Location $root
Write-Host '=== DIAGNOSTICO SEGURIDAD v0.10.5 ===' -ForegroundColor Cyan
docker compose ps
$cfg=@{};Get-Content '.env' -ErrorAction SilentlyContinue|%{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'};$port=if($cfg['ERP_PORT']){$cfg['ERP_PORT']}else{'8080'}
Write-Host "`n--- Backend build ---" -ForegroundColor Yellow
try{Invoke-RestMethod "http://localhost:$port/api/system/build"|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway V16 ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history where version in ('16') order by installed_rank;"
Write-Host "`n--- Seguridad usuarios ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select email,active,must_change_password,failed_login_attempts,locked_until,last_login_at,security_version from app_users order by email;"
Write-Host "`n--- Sesiones activas ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select u.email,s.issued_at,s.expires_at,s.last_seen_at,s.revoked_at,s.ip_address from auth_sessions s join app_users u on u.id=s.user_id order by s.issued_at desc limit 30;"
Write-Host "`n--- Backups catalogados ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select filename,size_bytes,status,created_at,verified_at,restored_at from system_backup_history order by created_at desc limit 20;"
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
docker compose logs backend --tail=120
Read-Host 'Presione Enter para cerrar'
