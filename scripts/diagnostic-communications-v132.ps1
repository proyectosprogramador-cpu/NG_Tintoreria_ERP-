$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path);Set-Location $root
$cfg=@{};if(Test-Path '.env'){Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
Write-Host '=== NG Tintoreria ERP v0.13.2 - Diagnostico Comunicaciones ===' -ForegroundColor Cyan
Write-Host "DRY_RUN: $(if($cfg['COPILOT_NOTIFY_DRY_RUN']){$cfg['COPILOT_NOTIFY_DRY_RUN']}else{'true (default)'})"
Write-Host "SMTP_HOST: $(if($cfg['SMTP_HOST']){'CONFIGURADO'}else{'NO CONFIGURADO'})"
Write-Host "WHATSAPP_WEBHOOK: $(if($cfg['COPILOT_WHATSAPP_WEBHOOK_URL']){'CONFIGURADO'}else{'NO CONFIGURADO'})"
Write-Host '`nFlyway:' -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history order by installed_rank desc limit 4;"
Write-Host '`nReglas:' -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select category,min_severity,channels,role_codes,enabled,repeat_minutes,quiet_start,quiet_end from copilot_notification_rules order by category;"
Write-Host '`nDestinatarios:' -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select name,role_code,email,whatsapp,email_enabled,whatsapp_enabled,active from copilot_notification_contacts order by role_code,name;"
Write-Host '`nUltimos mensajes:' -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select created_at,channel,recipient,category,severity,status,left(coalesce(last_error,provider_response,''),120) detalle from copilot_notification_outbox order by created_at desc limit 20;"
Write-Host '`nServicios:' -ForegroundColor Yellow
docker compose ps
Read-Host 'Presione Enter para cerrar'
