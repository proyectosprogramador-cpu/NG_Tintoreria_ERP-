import React,{useEffect,useState} from 'react';

const API='/api';
function token(){return localStorage.getItem('ngerp_token')||''}
async function req(path:string,opts:RequestInit={}){const h:any={'Content-Type':'application/json',...(opts.headers||{})};if(token())h.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{...opts,headers:h});const txt=await r.text();let body:any=null;try{body=txt?JSON.parse(txt):null}catch{body=txt}if(!r.ok)throw new Error(body?.message||body?.error||txt||`HTTP ${r.status}`);return body}
function bytes(n:any){const x=Number(n||0);if(x<1024)return `${x} B`;if(x<1024**2)return `${(x/1024).toFixed(1)} KB`;if(x<1024**3)return `${(x/1024**2).toFixed(1)} MB`;return `${(x/1024**3).toFixed(2)} GB`}
function date(v:any){return v?new Date(v).toLocaleString():'—'}

export function SecurityCenter({canManage,onError}:{canManage:boolean,onError:(s:string)=>void}){
 const [summary,setSummary]=useState<any>(null),[sessions,setSessions]=useState<any[]>([]),[backups,setBackups]=useState<any[]>([]),[tab,setTab]=useState('health');
 const load=async()=>{try{const [a,b,c]=await Promise.all([req('/security-admin/summary'),req('/security-admin/sessions'),req('/security-admin/backups?limit=50')]);setSummary(a);setSessions(b);setBackups(c)}catch(e:any){onError(e.message)}};
 useEffect(()=>{void load()},[]);
 async function revoke(id:string){if(!confirm('¿Revocar esta sesión activa? El usuario deberá iniciar sesión nuevamente.'))return;try{await req('/security-admin/sessions/'+id,{method:'DELETE'});await load()}catch(e:any){onError(e.message)}}
 return <div>
  <div className="hero"><div><h2>Seguridad y recuperación</h2><p>Sesiones, bloqueos, auditoría, estado de base de datos y catálogo de backups verificados.</p></div><div className="badge">Administración</div></div>
  <div className="tabs"><button className={tab==='health'?'active':''} onClick={()=>setTab('health')}>Salud y seguridad</button><button className={tab==='sessions'?'active':''} onClick={()=>setTab('sessions')}>Sesiones activas</button><button className={tab==='backups'?'active':''} onClick={()=>setTab('backups')}>Backups</button><button onClick={load}>Actualizar</button></div>
  {tab==='health'&&<div>
    <div className="kpis">
      <K label="Base de datos" value={summary?.database||'...'} sub="Conectividad PostgreSQL"/>
      <K label="Usuarios activos" value={summary?.activeUsers??'...'} sub="Cuentas habilitadas"/>
      <K label="Usuarios bloqueados" value={summary?.lockedUsers??'...'} sub="Bloqueo temporal por intentos"/>
      <K label="Intentos fallidos 24 h" value={summary?.failedLogins24h??'...'} sub="Eventos de autenticación"/>
      <K label="Sesiones activas" value={summary?.activeSessions??'...'} sub="JWT vigentes y no revocados"/>
      <K label="Tamaño BD" value={summary?bytes(summary.databaseBytes):'...'} sub="Tamaño lógico actual"/>
    </div>
    <div className="panel"><div className="panelhead"><div><h3>Último backup registrado</h3><p>El backup se genera fuera del navegador para no entregar al proceso web permisos sobre Docker o el sistema operativo.</p></div></div>
      {summary?.lastBackup?<div className="formgrid"><div><b>Archivo</b><br/>{summary.lastBackup.filename}</div><div><b>Estado</b><br/>{summary.lastBackup.status}</div><div><b>Tamaño</b><br/>{bytes(summary.lastBackup.size_bytes)}</div><div><b>Creado</b><br/>{date(summary.lastBackup.created_at)}</div><div><b>Verificado</b><br/>{date(summary.lastBackup.verified_at)}</div><div><b>SHA-256</b><br/><code>{summary.lastBackup.sha256||'—'}</code></div></div>:<p>No hay backups integrales registrados todavía. Ejecute <b>CREAR_BACKUP_SEGURO_WINDOWS.bat</b>.</p>}
    </div>
    <div className="panel"><div className="panelhead"><div><h3>Política activa</h3><p>Controles incorporados en esta versión.</p></div></div><div className="checks security-list"><label>✓ 5 intentos fallidos bloquean temporalmente la cuenta</label><label>✓ JWT asociados a una sesión revocable</label><label>✓ Cambio de contraseña revoca todas las sesiones</label><label>✓ Contraseña: 10+ caracteres, mayúscula, minúscula, número y símbolo</label><label>✓ Auditoría registra IP, método, ruta y resultado</label><label>✓ Backup integral incluye BD, documentos/evidencias y logo</label><label>✓ Restauración crea un backup integral antes de tocar la base</label></div></div>
  </div>}
  {tab==='sessions'&&<div className="panel"><div className="panelhead"><div><h3>Sesiones activas</h3><p>Puede revocar una sesión sin desactivar al usuario.</p></div></div><div className="tablewrap"><table><thead><tr><th>Usuario</th><th>IP</th><th>Inicio</th><th>Última actividad</th><th>Expira</th><th>Dispositivo</th><th/></tr></thead><tbody>{sessions.map(s=><tr key={s.id}><td><b>{s.displayName}</b><br/><small>{s.user}</small></td><td>{s.ipAddress||'—'}</td><td>{date(s.issuedAt)}</td><td>{date(s.lastSeenAt)}</td><td>{date(s.expiresAt)}</td><td className="wrapcell">{s.userAgent||'—'}</td><td>{canManage&&<button onClick={()=>revoke(s.id)}>Revocar</button>}</td></tr>)}{!sessions.length&&<tr><td colSpan={7}>No hay sesiones activas.</td></tr>}</tbody></table></div></div>}
  {tab==='backups'&&<div className="panel"><div className="panelhead"><div><h3>Historial de backups y restauraciones</h3><p>Use los scripts Windows incluidos. La restauración no se expone desde el navegador por seguridad.</p></div></div><div className="inline-note"><b>Crear:</b> CREAR_BACKUP_SEGURO_WINDOWS.bat &nbsp; · &nbsp; <b>Verificar:</b> VERIFICAR_BACKUPS_WINDOWS.bat &nbsp; · &nbsp; <b>Restaurar:</b> RESTAURAR_BACKUP_WINDOWS.bat</div><div className="tablewrap"><table><thead><tr><th>Fecha</th><th>Archivo</th><th>Tamaño</th><th>Estado</th><th>Verificado</th><th>Restaurado</th><th>SHA-256</th></tr></thead><tbody>{backups.map((b:any)=><tr key={b.id}><td>{date(b.created_at)}</td><td>{b.filename}</td><td>{bytes(b.size_bytes)}</td><td>{b.status}</td><td>{date(b.verified_at)}</td><td>{date(b.restored_at)}</td><td><code>{b.sha256?String(b.sha256).slice(0,16)+'…':'—'}</code></td></tr>)}{!backups.length&&<tr><td colSpan={7}>Aún no hay backups integrales registrados.</td></tr>}</tbody></table></div></div>}
 </div>
}
function K({label,value,sub}:{label:string,value:any,sub:string}){return <div className="kpi"><small>{label}</small><b>{value}</b><span>{sub}</span></div>}
