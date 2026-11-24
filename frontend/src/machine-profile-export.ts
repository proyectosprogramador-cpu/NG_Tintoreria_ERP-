/** Exporta la ficha técnica sin dependencias externas. */
type Field = {label:string;value:string};
type Section = {title:string;fields:Field[]};
type Sheet = {code:string;name:string;sections:Section[];issuedAt:string};

const statusLabels:Record<string,string> = {
 AVAILABLE:'Disponible',IN_MAINTENANCE:'En mantenimiento',
 OUT_OF_SERVICE:'Fuera de servicio',RETIRED:'Retirada'
};
const healthLabels:Record<string,string> = {
 OK:'Al día',DUE_SOON:'Próximo',OVERDUE:'Vencido',
 IN_MAINTENANCE:'En mantenimiento',OUT_OF_SERVICE:'Fuera de servicio',RETIRED:'Retirada'
};
const value = (v:unknown):string => v===null||v===undefined||String(v).trim()===''?'No registrado':String(v);
const numberText=(v:unknown,unit:string):string => v===null||v===undefined||String(v).trim()===''?'No registrado':`${Number(v).toLocaleString('es-PE',{maximumFractionDigits:2})} ${unit}`;
const dateText=(v:unknown):string => {
 if(!v)return 'No registrada';
 const s=String(v);
 if(/^\d{4}-\d{2}-\d{2}$/.test(s))return s.split('-').reverse().join('/');
 const d=new Date(s);
 return Number.isNaN(d.getTime())?s:d.toLocaleDateString('es-PE');
};
const timestamp=(v:unknown):string => {
 if(!v)return 'No registrado';
 const d=new Date(String(v));
 return Number.isNaN(d.getTime())?String(v):d.toLocaleString('es-PE');
};
export function machineSheet(machine:any):Sheet {
 return {
  code:value(machine.code),name:value(machine.name),
  issuedAt:new Date().toLocaleString('es-PE'),
  sections:[
   {title:'IDENTIFICACIÓN DEL EQUIPO',fields:[
    {label:'Código',value:value(machine.code)},
    {label:'Nombre del equipo',value:value(machine.name)},
    {label:'Marca',value:value(machine.brand)},
    {label:'Modelo',value:value(machine.model)},
    {label:'Número de serie',value:value(machine.serialNumber??machine.serial_number)},
    {label:'Ubicación',value:value(machine.location)},
    {label:'Fecha de puesta en servicio',value:dateText(machine.commissioningDate??machine.commissioning_date)}
   ]},
   {title:'OPERACIÓN Y MANTENIMIENTO',fields:[
    {label:'Estado operativo',value:statusLabels[machine.operationalStatus??machine.operational_status]||value(machine.operationalStatus??machine.operational_status)},
    {label:'Horómetro actual',value:numberText(machine.currentMeterHours??machine.current_meter_hours,'h')},
    {label:'Preventivo cada (horas)',value:numberText(machine.maintenanceIntervalHours??machine.maintenance_interval_hours,'h')},
    {label:'Preventivo cada (días)',value:numberText(machine.maintenanceIntervalDays??machine.maintenance_interval_days,'dias')},
    {label:'Salud del equipo',value:healthLabels[machine.maintenance_health]||value(machine.maintenance_health)},
    {label:'Último mantenimiento',value:timestamp(machine.last_maintenance_at)},
    {label:'Próximo mantenimiento',value:timestamp(machine.next_maintenance_at)},
    {label:'OT abiertas',value:String(machine.open_work_orders??0)}
   ]}
  ]
 };
}

const esc=(text:string)=>text.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]||c));

/** Imprime la ficha en una ventana sin controles del ERP. */
export function printMachineSheet(machine:any):void {
 const sheet=machineSheet(machine);
 const popup=window.open('','_blank','width=950,height=850');
 if(!popup)throw new Error('El navegador bloqueo la ventana de impresion. Habilite ventanas emergentes para este ERP.');
 const sections=sheet.sections.map(section=>`<section><h2>${esc(section.title)}</h2><div class="fields">${section.fields.map(f=>`<div class="field"><small>${esc(f.label)}</small><strong>${esc(f.value)}</strong></div>`).join('')}</div></section>`).join('');
 const html=`<!doctype html><html lang="es"><head><meta charset="utf-8"><title>Ficha tecnica ${esc(sheet.code)}</title><style>
 @page {size:A4;margin:17mm 16mm;}*{box-sizing:border-box}body{font:12px Arial,sans-serif;color:#213447;margin:0}
 .head{display:flex;justify-content:space-between;align-items:center;border-bottom:3px solid #14629a;padding:0 0 17px}
 .brand{display:flex;gap:12px;align-items:center}.mark{font-size:20px;background:#11517b;color:white;padding:11px;border-radius:10px;font-weight:800}
 h1{font-size:21px;margin:3px 0 4px}h2{font-size:12px;letter-spacing:1px;border-bottom:1px solid #d8e2eb;padding:12px 0;margin:16px 0 5px}
 .sub{color:#617386;font-size:11px}.chip{background:#eaf2f8;border-radius:7px;padding:9px;text-align:right;font-size:11px}
 .fields{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}.field{min-height:49px;padding:9px 0;border-bottom:1px solid #e5ebf0;break-inside:avoid}
 .field small{display:block;color:#64778b;margin-bottom:6px}.field strong{font-size:12px;font-weight:600;overflow-wrap:anywhere}
 .footer{margin-top:25px;border-top:1px solid #cbd7e0;padding-top:12px;color:#617386;font-size:10px}
 .signature{margin-top:60px;display:flex;gap:65px}.signature>span{flex:1;border-top:1px solid #718497;text-align:center;padding-top:7px}
 @media print {.head,.field,section{break-inside:avoid}}
 </style></head><body><div class="head"><div class="brand"><div class="mark">NG</div><div><div class="sub">NG TINTORERIA ERP</div><h1>Ficha tecnica de maquina</h1><div class="sub">Identificacion y mantenimiento del equipo industrial</div></div></div><div class="chip">${esc(sheet.code)}<br>${esc(sheet.issuedAt)}</div></div>
 ${sections}<div class="footer">Documento generado desde NG Tintoreria ERP. Los datos corresponden al registro disponible en el sistema al momento de su emision.</div>
 <div class="signature"><span>Responsable de Mantenimiento</span><span>Revisión / autorización</span></div></body></html>`;
 popup.addEventListener('load',()=>{popup.focus();popup.print()},{once:true});
 popup.document.open();popup.document.write(html);popup.document.close();
}

/** Codifica acentos y eñes con escapes octales compatibles con WinAnsi. */
function pdfLiteral(input:string):string {
 const substitutions:Record<string,string>={'—':'-','–':'-','‘':"'",'’':"'",'“':'"','”':'"','…':'...','•':'-'};
 return Array.from(input.replace(/\s+/g,' ')).map(ch=>{
  ch=substitutions[ch]||ch;
  return Array.from(ch).map(char=>{
   if(char==='\\')return '\\\\';
   if(char==='(')return '\\(';
   if(char===')')return '\\)';
   const cp=char.charCodeAt(0);
   if(cp>=32&&cp<=126)return char;
   if(cp>=160&&cp<=255)return '\\'+cp.toString(8).padStart(3,'0');
   return '?';
  }).join('');
 }).join('');
}
const cropped=(s:string,max=46)=>s.length<=max?s:s.slice(0,max-3)+'...';

/** Descarga la ficha en un PDF A4 de una página. */
export function downloadMachineSheetPdf(machine:any):void {
 const sheet=machineSheet(machine);
 const parts:string[]=[];
 const rect=(x:number,y:number,w:number,h:number,r:number,g:number,b:number)=>parts.push(`${r} ${g} ${b} rg ${x} ${y} ${w} ${h} re f`);
 const text=(x:number,y:number,s:string,size=10,bold=false,r=0.13,g=0.22,b=0.33)=>{
  parts.push(`${r} ${g} ${b} rg BT /${bold?'F2':'F1'} ${size} Tf 1 0 0 1 ${x} ${y} Tm (${pdfLiteral(s)}) Tj ET`);
 };
 const line=(x:number,y:number,x2:number,y2:number)=>parts.push(`0.84 0.89 0.93 RG 0.6 w ${x} ${y} m ${x2} ${y2} l S`);
 rect(0,736,595,106,0.06,0.19,0.29);
 rect(40,759,52,52,0.11,0.38,0.58);
 text(50,777,'NG',21,true,1,1,1);
 text(110,796,'NG TINTORERIA ERP',10,true,0.77,0.86,0.94);
 text(110,773,'FICHA TÉCNICA DE MÁQUINA',16,true,1,1,1);
 text(110,753,'Documento de control de equipo industrial',9,false,0.78,0.87,0.94);
 text(41,713,`Equipo: ${cropped(sheet.code+' - '+sheet.name,80)}`,11,true);
 text(41,697,`Fecha de emisión: ${sheet.issuedAt}`,9);
 let y=667;
 for(const section of sheet.sections){
  rect(40,y-4,515,24,0.92,0.95,0.975);
  text(48,y+4,section.title,10,true,0.07,0.29,0.45);
  y-=18;
  for(let i=0;i<section.fields.length;i+=2){
   for(let j=0;j<2;j++){
    const field=section.fields[i+j];if(!field)continue;
    const x=j===0?44:302;
    text(x,y-12,field.label.toUpperCase(),8,false,0.39,0.47,0.54);
    text(x,y-29,cropped(field.value,42),10,true);
    line(x,y-37,x+244,y-37);
   }
   y-=52;
  }
  y-=17;
 }
 const footerY=Math.max(96,y-6);
 line(40,footerY,555,footerY);
 text(42,footerY-16,'Documento de consulta. No constituye una orden de mantenimiento.',8);
 line(42,75,248,75);line(344,75,551,75);
 text(75,62,'Responsable de Mantenimiento',8);
 text(380,62,'Revisión / autorización',8);
 text(40,32,`NG Tintoreria ERP   |   ${sheet.code}`,8,false,0.42,0.50,0.57);
 text(518,32,'Pag. 1/1',8,false,0.42,0.50,0.57);
 const stream=parts.join('\n')+'\n';
 const objects=[
  '<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>',
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>',
  `<< /Length ${new TextEncoder().encode(stream).length} >>\nstream\n${stream}endstream`
 ];
 let pdf='%PDF-1.4\n';
 const positions=[0];
 for(let i=0;i<objects.length;i++){
  positions.push(new TextEncoder().encode(pdf).length);
  pdf+=`${i+1} 0 obj\n${objects[i]}\nendobj\n`;
 }
 const xref=new TextEncoder().encode(pdf).length;
 pdf+=`xref\n0 ${objects.length+1}\n0000000000 65535 f \n`;
 for(let i=1;i<positions.length;i++)pdf+=`${String(positions[i]).padStart(10,'0')} 00000 n \n`;
 pdf+=`trailer\n<< /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
 const blob=new Blob([pdf],{type:'application/pdf'});
 const url=URL.createObjectURL(blob);
 const a=document.createElement('a');a.href=url;a.download=`Ficha_Tecnica_${sheet.code.replace(/[^A-Za-z0-9_-]/g,'_')}.pdf`;
 document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1500);
}
