import React,{useState} from 'react';

type Props={authorities:Set<string>;onError:(message:string)=>void};
const API='/api';const token=()=>localStorage.getItem('ngerp_token')||'';const iso=(d:Date)=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
async function file(path:string,open=false){const headers:Record<string,string>={};if(token())headers.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{headers});if(!r.ok)throw new Error(await r.text()||`HTTP ${r.status}`);const blob=await r.blob();const cd=r.headers.get('content-disposition')||'';const utf=cd.match(/filename\*=UTF-8''([^;]+)/i),simple=cd.match(/filename="?([^";]+)"?/i);const name=utf?decodeURIComponent(utf[1]):simple?.[1]||'reporte';const url=URL.createObjectURL(blob);if(open){window.open(url,'_blank','noopener,noreferrer');setTimeout(()=>URL.revokeObjectURL(url),60000);return}const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1500)}
const can=(a:Set<string>,p:string)=>a.has(p)||a.has('ROLE_ADMIN');
// El perfil exclusivo PRODUCTION solo puede exportar estos informes operativos.
const productionReports=new Set(['production/orders','production/consumption','production/incidents']);
const isProductionOnly=(a:Set<string>)=>a.has('ROLE_PRODUCTION')&&![...a].some(p=>p.startsWith('ROLE_')&&p!=='ROLE_PRODUCTION');
export function ReportsCenter({authorities,onError}:Props){const end=new Date(),start=new Date();start.setDate(end.getDate()-29);const [from,setFrom]=useState(iso(start)),[to,setTo]=useState(iso(end));const q=()=>`?from=${from}&to=${to}`;
 const reports=[
  {group:'Gerencia',title:'Resumen gerencial',desc:'Pedidos, producción, calidad, entregas, inventario y rentabilidad.',path:'dashboard/executive',perm:'COSTING_READ',period:true},
  {group:'Comercial',title:'Cotizaciones / proformas',desc:'Histórico comercial con estado, cliente, moneda y total.',path:'commercial/quotations',perm:'QUOTES_READ',period:true},
  {group:'Comercial',title:'Pedidos',desc:'Pedidos, prioridad, fecha requerida, estado y total.',path:'commercial/orders',perm:'ORDERS_READ',period:true},
  {group:'Operación',title:'Recepciones de tela',desc:'Rollos, kg, metros, guía y liberación.',path:'receiving/receptions',perm:'RECEPTIONS_READ',period:true},
  {group:'Operación',title:'Laboratorio de color',desc:'Lab dips, estándar, alternativa y estado.',path:'laboratory/requests',perm:'LAB_REQUESTS_READ',period:true},
  {group:'Operación',title:'Órdenes de producción',desc:'OP, lotes, máquina, estado, kilos y tiempos.',path:'production/orders',perm:'PRODUCTION_ORDERS_READ'},
  {group:'Operación',title:'Consumo teórico vs. real',desc:'Consumos planificados y reales por OP.',path:'production/consumption',perm:'PRODUCTION_ORDERS_READ'},
  {group:'Operación',title:'Incidencias de producción',desc:'Paradas, severidad, causa y acción correctiva.',path:'production/incidents',perm:'PRODUCTION_ORDERS_READ'},
  {group:'Mantenimiento',title:'Estado de máquinas',desc:'Horómetro, estado operativo y próximo mantenimiento.',path:'maintenance/machines',perm:'MAINTENANCE_READ'},
  {group:'Mantenimiento',title:'Órdenes de mantenimiento',desc:'Preventivos, correctivos, emergencias, costos y parada.',path:'maintenance/work-orders',perm:'MAINTENANCE_READ'},
  {group:'Calidad',title:'Inspecciones de calidad',desc:'Resultados, reprocesos, rechazo y aprobación.',path:'quality/inspections',perm:'QUALITY_READ'},
  {group:'Calidad',title:'Pruebas de calidad',desc:'Parámetros, objetivo, valor medido y evaluación.',path:'quality/checks',perm:'QUALITY_READ'},
  {group:'Almacén',title:'Existencias',desc:'Físico, reservado, disponible, mínimo y en compra.',path:'inventory/stock',perm:'INVENTORY_READ'},
  {group:'Almacén',title:'Kardex',desc:'Entradas, salidas, lote, saldo, referencia y usuario.',path:'inventory/kardex',perm:'INVENTORY_READ'},
  {group:'Almacén',title:'Reservas por OP',desc:'Stock comprometido y liberaciones por producción.',path:'inventory/reservations',perm:'INVENTORY_READ'},
  {group:'Compras',title:'Solicitudes de compra',desc:'Solicitudes originadas por faltantes y reposición.',path:'purchase-requests',perm:'PURCHASE_REQUESTS_READ'},
  {group:'Compras',title:'Órdenes de compra',desc:'Proveedor, estado, moneda, recepción y total.',path:'purchase-orders',perm:'PURCHASE_ORDERS_READ'},
  {group:'Despacho',title:'Despachos y entregas',desc:'Salida, transporte, guía, kilos y recepción del cliente.',path:'dispatches',perm:'DISPATCH_READ',period:true},
  {group:'Postventa',title:'Devoluciones y reprocesos',desc:'Reclamos post-despacho, kilos devueltos, resolución y OP de reproceso.',path:'returns',perm:'RETURNS_READ'},
  {group:'Gestión documental',title:'Registro documental',desc:'Documentos, versiones, categorías, registros relacionados y responsables de carga.',path:'documents/register',perm:'ROLE_ADMIN'},
  {group:'Costos',title:'Costeo por OP',desc:'Costo real, venta asignada y margen por producción.',path:'costing/orders',perm:'COSTING_READ'},
  {group:'Costos',title:'Costos de materiales',desc:'Valorización de consumos reales por insumo y OP.',path:'costing/materials',perm:'COSTING_READ'},
  {group:'Piloto',title:'Checklist de puesta en marcha',desc:'Validaciones obligatorias, responsables, evidencia y estado para salida productiva.',path:'pilot/checklist',perm:'PILOT_READ'},
  {group:'Piloto',title:'Incidencias del piloto',desc:'Incidencias por severidad, módulo, estado, resolución y verificación.',path:'pilot/incidents',perm:'PILOT_READ'},
  {group:'Administración',title:'Clientes',desc:'Maestro de clientes, contacto, crédito y estado.',path:'admin/clients',perm:'CLIENTS_READ'},
  {group:'Administración',title:'Proveedores',desc:'Maestro de proveedores, contacto, dirección y condición de pago.',path:'admin/suppliers',perm:'SUPPLIERS_READ'},
  {group:'Administración',title:'Listas de precios',desc:'Vigencia, moneda, prioridad y rango de precios por lista.',path:'admin/price-lists',perm:'PRICING_READ'},
  {group:'Administración',title:'Fórmulas',desc:'Recetas maestras, versión vigente y parámetros principales.',path:'admin/formulas',perm:'FORMULAS_READ'},
  {group:'Administración',title:'Maestros técnicos',desc:'Telas, colores, procesos, acabados, unidades y máquinas.',path:'admin/masters',perm:'MASTERS_READ'},
  {group:'Administración',title:'Usuarios y roles',desc:'Usuarios, roles, estado de acceso y último inicio de sesión.',path:'admin/users',perm:'USERS_READ'},
  {group:'Administración',title:'Auditoría',desc:'Usuario, módulo, acción, referencia y detalle.',path:'audit/events',perm:'AUDIT_READ',period:true}
 ].filter(x=>can(authorities,'REPORTS_EXPORT')&&can(authorities,x.perm)&&(!isProductionOnly(authorities)||productionReports.has(x.path)));
 const groups=[...new Set(reports.map(x=>x.group))];
 function run(r:any,ext:string,open=false){const query=r.period?q():'';file(`/reports/${r.path}.${ext}${query}`,open).catch((e:any)=>onError(e.message))}
 return <><div className="hero"><div><h2>Centro de reportes</h2><p>Descargue Excel o PDF por área sin depender de capturas de pantalla. Los reportes respetan los permisos del usuario.</p></div><div className="badge">Excel · PDF</div></div><div className="panel report-filter"><div><b>Periodo para reportes compatibles</b><small>Resumen gerencial, Comercial, Recepciones, Laboratorio, Despachos y Auditoría.</small></div><div className="dash-dates"><input type="date" value={from} onChange={e=>setFrom(e.target.value)}/><span>—</span><input type="date" value={to} onChange={e=>setTo(e.target.value)}/></div></div>{groups.map(g=><div className="panel" key={g}><div className="panelhead"><div><h3>{g}</h3><p>{reports.filter(x=>x.group===g).length} reporte(s) disponible(s)</p></div></div><div className="report-grid">{reports.filter(x=>x.group===g).map(r=><div className="report-card" key={r.path}><div><b>{r.title}</b><p>{r.desc}</p>{r.period&&<small>Filtrado: {from} → {to}</small>}{!r.period&&<small>Histórico completo</small>}</div><div className="exportbar"><button onClick={()=>run(r,'xlsx')}>Excel</button><button onClick={()=>run(r,'pdf')}>PDF</button><button onClick={()=>run(r,'pdf',true)}>Imprimir</button></div></div>)}</div></div>)}</>;
}
