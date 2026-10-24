package com.ngtintoreria.erp.reports;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.ngtintoreria.erp.company.CompanyLogoStorageService;
import com.ngtintoreria.erp.procurement.PurchaseOrderService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReportService {
    private final JdbcTemplate jdbc;
    private final PurchaseOrderService purchaseOrders;
    private final CompanyLogoStorageService companyLogos;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public record ReportFile(byte[] bytes,String contentType,String filename){}
    private record Col(String title,String key){}

    public ReportService(JdbcTemplate jdbc,PurchaseOrderService purchaseOrders,CompanyLogoStorageService companyLogos){this.jdbc=jdbc;this.purchaseOrders=purchaseOrders;this.companyLogos=companyLogos;}

    public ReportFile inventoryStock(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
            reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id),
            purchasing as (
              select poi.inventory_item_id,sum(greatest(poi.quantity-poi.received_quantity,0)) qty
              from purchase_order_items poi join purchase_orders po on po.id=poi.purchase_order_id
              where po.status in ('ISSUED','SENT','PARTIALLY_RECEIVED') group by poi.inventory_item_id
            )
            select i.code,i.name,i.base_unit,coalesce(p.qty,0) physical_quantity,coalesce(r.qty,0) reserved_quantity,
                   greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0) available_quantity,coalesce(pc.qty,0) in_purchase_quantity,
                   i.minimum_stock,i.safety_stock,
                   case when coalesce(p.qty,0)=0 then 'SIN STOCK'
                        when greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock then 'STOCK BAJO' else 'NORMAL' end stock_status
              from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id
              left join purchasing pc on pc.inventory_item_id=i.id where i.active=true order by lower(i.name)
            """);
        List<Col> cols=List.of(new Col("Código","code"),new Col("Insumo","name"),new Col("Unidad","base_unit"),new Col("Físico","physical_quantity"),new Col("Reservado","reserved_quantity"),new Col("Disponible","available_quantity"),new Col("En compra","in_purchase_quantity"),new Col("Mínimo","minimum_stock"),new Col("Seguridad","safety_stock"),new Col("Estado","stock_status"));
        return tabular("Existencias de almacén",rows,cols,"existencias_almacen",format);
    }

    public ReportFile inventoryKardex(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select m.created_at,i.code item_code,i.name item_name,l.lot_code,w.name warehouse_name,m.movement_type,m.quantity,m.balance_after,
                   m.reference_number,m.comment,m.actor
              from inventory_movements m join inventory_items i on i.id=m.inventory_item_id join warehouses w on w.id=m.warehouse_id
              left join inventory_lots l on l.id=m.inventory_lot_id order by m.created_at desc,m.id desc limit 5000
            """);
        List<Col> cols=List.of(new Col("Fecha","created_at"),new Col("Código","item_code"),new Col("Insumo","item_name"),new Col("Lote","lot_code"),new Col("Almacén","warehouse_name"),new Col("Movimiento","movement_type"),new Col("Cantidad","quantity"),new Col("Saldo","balance_after"),new Col("Referencia","reference_number"),new Col("Comentario","comment"),new Col("Usuario","actor"));
        return tabular("Kardex de insumos",rows,cols,"kardex_insumos",format);
    }

    public ReportFile inventoryReservations(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.batch_number,po.priority,i.code item_code,i.name item_name,r.quantity,pmr.planned_quantity_kg,r.status,r.created_at,r.created_by,r.release_reason
              from inventory_reservations r join inventory_items i on i.id=r.inventory_item_id
              join production_orders po on po.id=r.production_order_id
              join production_material_requirements pmr on pmr.id=r.production_material_requirement_id
             order by r.created_at desc
            """);
        List<Col> cols=List.of(new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Prioridad","priority"),new Col("Código","item_code"),new Col("Insumo","item_name"),new Col("Reservado","quantity"),new Col("Requerido","planned_quantity_kg"),new Col("Estado","status"),new Col("Fecha","created_at"),new Col("Usuario","created_by"),new Col("Motivo liberación","release_reason"));
        return tabular("Reservas por orden de producción",rows,cols,"reservas_op",format);
    }

    public ReportFile purchaseRequests(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pr.request_number,pr.status,pr.priority,pr.requested_at,pr.requested_by,pr.approved_by,pr.approved_at,
                   po.production_number,po.batch_number,c.name client_name,count(pri.id) line_count,coalesce(sum(pri.requested_quantity),0) requested_quantity
              from purchase_requests pr left join production_orders po on po.id=pr.production_order_id left join clients c on c.id=po.client_id
              left join purchase_request_items pri on pri.purchase_request_id=pr.id
             group by pr.id,po.production_number,po.batch_number,c.name order by pr.requested_at desc
            """);
        List<Col> cols=List.of(new Col("Solicitud","request_number"),new Col("Estado","status"),new Col("Prioridad","priority"),new Col("Fecha","requested_at"),new Col("Solicitante","requested_by"),new Col("Aprobado por","approved_by"),new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),new Col("Líneas","line_count"),new Col("Cantidad solicitada","requested_quantity"));
        return tabular("Solicitudes de compra",rows,cols,"solicitudes_compra",format);
    }

    public ReportFile purchaseOrderList(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.order_number,po.order_date,po.expected_date,po.status,s.ruc supplier_ruc,s.name supplier_name,pr.request_number,
                   po.currency,po.subtotal,po.discount,po.igv_amount,po.total,po.created_by,po.issued_by,po.issued_at,po.sent_at
              from purchase_orders po left join suppliers s on s.id=po.supplier_id left join purchase_requests pr on pr.id=po.purchase_request_id
             order by po.order_date desc,po.created_at desc
            """);
        List<Col> cols=List.of(new Col("OC","order_number"),new Col("Fecha","order_date"),new Col("Entrega","expected_date"),new Col("Estado","status"),new Col("RUC proveedor","supplier_ruc"),new Col("Proveedor","supplier_name"),new Col("Solicitud","request_number"),new Col("Moneda","currency"),new Col("Subtotal","subtotal"),new Col("Descuento","discount"),new Col("IGV","igv_amount"),new Col("Total","total"),new Col("Creado por","created_by"),new Col("Emitido por","issued_by"));
        return tabular("Órdenes de compra",rows,cols,"ordenes_compra",format);
    }

    public ReportFile productionOrders(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.batch_number,po.status,po.priority,c.name client_name,so.order_number,
                   m.code machine_code,m.name machine_name,po.batch_weight_kg,po.actual_output_weight_kg,
                   po.reprocess_weight_kg,po.waste_weight_kg,po.scheduled_start,po.scheduled_end,po.actual_start,po.actual_end,
                   case when po.actual_start is not null then round((extract(epoch from (coalesce(po.actual_end,now())-po.actual_start))/60)::numeric,1) else null end actual_minutes,
                   po.operator_name,po.material_status
              from production_orders po
              join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id
              join machines m on m.id=po.machine_id
             order by po.scheduled_start desc
            """);
        List<Col> cols=List.of(new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Estado","status"),new Col("Prioridad","priority"),new Col("Cliente","client_name"),new Col("Pedido","order_number"),new Col("Máquina","machine_code"),new Col("Nombre máquina","machine_name"),new Col("Kg planificados","batch_weight_kg"),new Col("Kg producidos","actual_output_weight_kg"),new Col("Kg reproceso","reprocess_weight_kg"),new Col("Kg merma","waste_weight_kg"),new Col("Inicio programado","scheduled_start"),new Col("Inicio real","actual_start"),new Col("Fin real","actual_end"),new Col("Min reales","actual_minutes"),new Col("Operario","operator_name"),new Col("Materiales","material_status"));
        return tabular("Órdenes de producción",rows,cols,"ordenes_produccion",format);
    }

    public ReportFile productionConsumption(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.batch_number,c.name client_name,pmr.line_no,pmr.material_code,pmr.material_name,
                   pmr.planned_quantity_kg,pmr.actual_quantity_kg,pmr.consumption_variance_kg,
                   pmr.consumption_confirmed,pmr.consumption_confirmed_by,pmr.consumption_confirmed_at
              from production_material_requirements pmr
              join production_orders po on po.id=pmr.production_order_id join clients c on c.id=po.client_id
             order by po.scheduled_start desc,pmr.line_no
            """);
        List<Col> cols=List.of(new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),new Col("Línea","line_no"),new Col("Código","material_code"),new Col("Insumo","material_name"),new Col("Teórico kg","planned_quantity_kg"),new Col("Real kg","actual_quantity_kg"),new Col("Variación kg","consumption_variance_kg"),new Col("Confirmado","consumption_confirmed"),new Col("Confirmado por","consumption_confirmed_by"),new Col("Fecha","consumption_confirmed_at"));
        return tabular("Consumo teórico vs real de producción",rows,cols,"consumos_produccion",format);
    }

    public ReportFile productionIncidents(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select po.production_number,po.batch_number,c.name client_name,pi.created_at,pi.incident_type,pi.severity,
                   pi.downtime_minutes,pi.description,pi.corrective_action,pi.actor
              from production_incidents pi join production_orders po on po.id=pi.production_order_id
              join clients c on c.id=po.client_id order by pi.created_at desc
            """);
        List<Col> cols=List.of(new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),new Col("Fecha","created_at"),new Col("Tipo","incident_type"),new Col("Severidad","severity"),new Col("Parada min","downtime_minutes"),new Col("Descripción","description"),new Col("Acción correctiva","corrective_action"),new Col("Usuario","actor"));
        return tabular("Incidencias de producción",rows,cols,"incidencias_produccion",format);
    }

    public ReportFile maintenanceMachines(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select m.code,m.name,m.operational_status,m.brand,m.model,m.serial_number,m.location,m.current_meter_hours,
                   m.maintenance_interval_hours,m.maintenance_interval_days,m.last_maintenance_at,m.last_maintenance_meter_hours,
                   m.next_maintenance_at,m.next_maintenance_meter_hours,
                   case when not m.active or coalesce(m.operational_status,'AVAILABLE')='RETIRED' then 'RETIRADA'
                        when coalesce(m.operational_status,'AVAILABLE')='OUT_OF_SERVICE' then 'FUERA DE SERVICIO'
                        when coalesce(m.operational_status,'AVAILABLE')='IN_MAINTENANCE' then 'EN MANTENIMIENTO'
                        when (m.next_maintenance_at is not null and m.next_maintenance_at<now()) or
                             (m.next_maintenance_meter_hours is not null and m.current_meter_hours>=m.next_maintenance_meter_hours) then 'VENCIDO'
                        when (m.next_maintenance_at is not null and m.next_maintenance_at<=now()+interval '7 days') or
                             (m.next_maintenance_meter_hours is not null and m.current_meter_hours>=m.next_maintenance_meter_hours-25) then 'PRÓXIMO'
                        else 'AL DÍA' end maintenance_health,
                   (select count(*) from maintenance_work_orders mw where mw.machine_id=m.id and mw.status in ('PLANNED','IN_PROGRESS')) open_work_orders
              from machines m order by lower(m.name)
            """);
        List<Col> cols=List.of(new Col("Código","code"),new Col("Máquina","name"),new Col("Estado","operational_status"),new Col("Marca","brand"),new Col("Modelo","model"),new Col("Serie","serial_number"),new Col("Ubicación","location"),new Col("Horómetro h","current_meter_hours"),new Col("Intervalo h","maintenance_interval_hours"),new Col("Intervalo días","maintenance_interval_days"),new Col("Último mantenimiento","last_maintenance_at"),new Col("Horómetro último","last_maintenance_meter_hours"),new Col("Próximo mantenimiento","next_maintenance_at"),new Col("Próximo horómetro","next_maintenance_meter_hours"),new Col("Salud","maintenance_health"),new Col("OT abiertas","open_work_orders"));
        return tabular("Estado de mantenimiento de máquinas",rows,cols,"mantenimiento_maquinas",format);
    }

    public ReportFile maintenanceWorkOrders(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select mw.maintenance_number,m.code machine_code,m.name machine_name,mw.maintenance_type,mw.priority,mw.status,mw.trigger_source,
                   mw.title,mw.scheduled_start,mw.scheduled_end,mw.actual_start,mw.actual_end,mw.meter_hours_start,mw.meter_hours_end,
                   mw.technician_name,mw.provider_name,mw.downtime_minutes,mw.labor_cost,mw.parts_cost,mw.external_cost,mw.total_cost,
                   mw.final_machine_status,mw.next_due_at,mw.next_due_meter_hours,mw.completed_by,mw.created_by
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id
             order by mw.scheduled_start desc
            """);
        List<Col> cols=List.of(new Col("OT","maintenance_number"),new Col("Máquina","machine_code"),new Col("Nombre","machine_name"),new Col("Tipo","maintenance_type"),new Col("Prioridad","priority"),new Col("Estado","status"),new Col("Origen","trigger_source"),new Col("Título","title"),new Col("Inicio programado","scheduled_start"),new Col("Fin programado","scheduled_end"),new Col("Inicio real","actual_start"),new Col("Fin real","actual_end"),new Col("Horómetro inicio","meter_hours_start"),new Col("Horómetro fin","meter_hours_end"),new Col("Técnico","technician_name"),new Col("Proveedor","provider_name"),new Col("Parada min","downtime_minutes"),new Col("Mano de obra","labor_cost"),new Col("Repuestos","parts_cost"),new Col("Externo","external_cost"),new Col("Total","total_cost"),new Col("Estado final","final_machine_status"),new Col("Próxima fecha","next_due_at"),new Col("Próximo horómetro","next_due_meter_hours"),new Col("Completado por","completed_by"));
        return tabular("Órdenes de mantenimiento",rows,cols,"ordenes_mantenimiento",format);
    }

    public ReportFile maintenanceWorkOrderPdf(UUID id){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select mw.maintenance_number,m.code machine_code,m.name machine_name,m.brand,m.model,m.serial_number,m.location,
                   mw.maintenance_type,mw.priority,mw.status,mw.title,mw.description,mw.scheduled_start,mw.scheduled_end,mw.actual_start,mw.actual_end,
                   mw.meter_hours_start,mw.meter_hours_end,mw.technician_name,mw.provider_name,mw.work_performed,mw.parts_used,mw.downtime_minutes,
                   mw.labor_cost,mw.parts_cost,mw.external_cost,mw.total_cost,mw.final_machine_status,mw.next_due_at,mw.next_due_meter_hours,mw.completed_by
              from maintenance_work_orders mw join machines m on m.id=mw.machine_id where mw.id=?
            """,id);
        if(rows.isEmpty())throw new IllegalArgumentException("La orden de mantenimiento no existe");
        Map<String,Object> h=rows.get(0);
        List<Col> cols=List.of(new Col("OT","maintenance_number"),new Col("Máquina","machine_code"),new Col("Tipo","maintenance_type"),new Col("Estado","status"),new Col("Título","title"),new Col("Programado","scheduled_start"),new Col("Inicio real","actual_start"),new Col("Fin real","actual_end"),new Col("Técnico","technician_name"),new Col("Horómetro fin","meter_hours_end"),new Col("Parada min","downtime_minutes"),new Col("Costo total","total_cost"),new Col("Estado final","final_machine_status"),new Col("Próximo","next_due_at"));
        return tabular("Orden de mantenimiento "+h.get("maintenance_number"),rows,cols,"mantenimiento_"+h.get("maintenance_number"),"pdf");
    }

    public ReportFile qualityInspections(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select qi.inspection_number,po.production_number,po.batch_number,c.name client_name,so.order_number,
                   qi.attempt_no,qi.status inspection_status,qi.result,qi.inspected_by,qi.inspected_at,
                   qi.decision_by,qi.decision_at,po.actual_output_weight_kg,qi.defect_summary,qi.general_notes
              from quality_inspections qi join production_orders po on po.id=qi.production_order_id
              join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id
             order by qi.created_at desc
            """);
        List<Col> cols=List.of(new Col("Inspección","inspection_number"),new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),new Col("Pedido","order_number"),new Col("Intento","attempt_no"),new Col("Estado","inspection_status"),new Col("Resultado","result"),new Col("Inspector","inspected_by"),new Col("Inspeccionado","inspected_at"),new Col("Decisión por","decision_by"),new Col("Decisión","decision_at"),new Col("Kg producidos","actual_output_weight_kg"),new Col("Defectos","defect_summary"),new Col("Observaciones","general_notes"));
        return tabular("Inspecciones de control de calidad",rows,cols,"inspecciones_calidad",format);
    }

    public ReportFile qualityChecks(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select qi.inspection_number,po.production_number,po.batch_number,c.name client_name,qc.line_no,
                   qc.check_type,qc.parameter_name,qc.target_value,qc.actual_value,qc.unit,qc.result,qc.notes,
                   qi.result inspection_result,qi.inspected_at
              from quality_checks qc join quality_inspections qi on qi.id=qc.quality_inspection_id
              join production_orders po on po.id=qi.production_order_id join clients c on c.id=po.client_id
             order by qi.created_at desc,qc.line_no
            """);
        List<Col> cols=List.of(new Col("Inspección","inspection_number"),new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),new Col("Línea","line_no"),new Col("Tipo","check_type"),new Col("Parámetro","parameter_name"),new Col("Objetivo","target_value"),new Col("Medido","actual_value"),new Col("Unidad","unit"),new Col("Evaluación","result"),new Col("Observación","notes"),new Col("Resultado inspección","inspection_result"),new Col("Fecha","inspected_at"));
        return tabular("Pruebas de control de calidad",rows,cols,"pruebas_calidad",format);
    }

    public ReportFile qualityInspectionPdf(UUID id){
        List<Map<String,Object>> headers=jdbc.queryForList("""
            select qi.*,po.production_number,po.batch_number,po.actual_output_weight_kg,po.waste_weight_kg,
                   c.name client_name,so.order_number,m.code machine_code,m.name machine_name,
                   f.name fabric_name,dc.name color_name,pt.name process_name,ft.name finish_name
              from quality_inspections qi join production_orders po on po.id=qi.production_order_id
              join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id join machines m on m.id=po.machine_id
              join formulas fo on fo.id=po.formula_id join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id
              join process_types pt on pt.id=fo.process_id left join finish_types ft on ft.id=fo.finish_id
             where qi.id=?
            """,id);
        if(headers.isEmpty())throw new IllegalArgumentException("La inspección de calidad no existe");
        Map<String,Object> h=headers.get(0),company=company();
        List<Map<String,Object>> checks=jdbc.queryForList("select line_no,parameter_name,target_value,actual_value,unit,result,notes from quality_checks where quality_inspection_id=? order by line_no",id);
        List<Map<String,Object>> evidences=jdbc.queryForList("select original_name,note,uploaded_by,created_at from quality_evidences where quality_inspection_id=? order by created_at",id);
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Document doc=new Document(PageSize.A4,32,32,34,34);PdfWriter.getInstance(doc,out);doc.open();
            Font title=new Font(Font.HELVETICA,15,Font.BOLD),h2=new Font(Font.HELVETICA,9,Font.BOLD),normal=new Font(Font.HELVETICA,8,Font.NORMAL),small=new Font(Font.HELVETICA,7,Font.NORMAL);
            doc.add(documentHeader(company,"CONTROL DE CALIDAD",str(h.get("inspection_number"),""),"Resultado: "+qualityResultEs(str(h.get("result"),"PENDING")),title,h2,small));doc.add(Chunk.NEWLINE);
            PdfPTable info=new PdfPTable(new float[]{1f,2f,1f,2f});info.setWidthPercentage(100);
            label(info,"OP",h2);value(info,str(h.get("production_number"),"—"),normal);label(info,"Lote",h2);value(info,str(h.get("batch_number"),"—"),normal);
            label(info,"Cliente",h2);value(info,str(h.get("client_name"),"—"),normal);label(info,"Pedido",h2);value(info,str(h.get("order_number"),"—"),normal);
            label(info,"Tela",h2);value(info,str(h.get("fabric_name"),"—"),normal);label(info,"Color",h2);value(info,str(h.get("color_name"),"—"),normal);
            label(info,"Proceso",h2);value(info,str(h.get("process_name"),"—"),normal);label(info,"Máquina",h2);value(info,str(h.get("machine_code"),"")+" · "+str(h.get("machine_name"),"—"),normal);
            label(info,"Peso producido",h2);value(info,num(h.get("actual_output_weight_kg"))+" kg",normal);label(info,"Intento",h2);value(info,str(h.get("attempt_no"),"1"),normal);doc.add(info);doc.add(Chunk.NEWLINE);
            PdfPTable table=new PdfPTable(new float[]{.45f,2f,1.45f,1.45f,.7f,1.15f,2f});table.setWidthPercentage(100);
            for(String x:List.of("#","Parámetro","Objetivo","Medido","Und.","Evaluación","Observación"))header(table,x,h2);
            for(Map<String,Object> x:checks){cell(table,str(x.get("line_no"),""),small);cell(table,str(x.get("parameter_name"),""),small);cell(table,str(x.get("target_value"),"—"),small);cell(table,str(x.get("actual_value"),"—"),small);cell(table,str(x.get("unit"),"—"),small);cell(table,checkResultEs(str(x.get("result"),"PENDING")),small);cell(table,str(x.get("notes"),""),small);}doc.add(table);doc.add(Chunk.NEWLINE);
            if(clean(h.get("defect_summary"))!=null){doc.add(new Paragraph("Defectos / no conformidades",h2));doc.add(new Paragraph(str(h.get("defect_summary"),""),normal));}
            if(clean(h.get("general_notes"))!=null){doc.add(new Paragraph("Observaciones generales",h2));doc.add(new Paragraph(str(h.get("general_notes"),""),normal));}
            if(clean(h.get("reprocess_instructions"))!=null){doc.add(new Paragraph("Instrucciones de reproceso",h2));doc.add(new Paragraph(str(h.get("reprocess_instructions"),""),normal));}
            if(!evidences.isEmpty()){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Evidencias registradas",h2));for(Map<String,Object> e:evidences)doc.add(new Paragraph("• "+str(e.get("original_name"),"")+" · "+str(e.get("uploaded_by"),"")+(clean(e.get("note"))==null?"":" · "+e.get("note")),small));}
            doc.add(Chunk.NEWLINE);PdfPTable sign=new PdfPTable(2);sign.setWidthPercentage(100);PdfPCell a=noBorder();a.setPaddingTop(20);a.addElement(new Paragraph("______________________________\nInspeccionó: "+str(h.get("inspected_by"),""),small));sign.addCell(a);PdfPCell b=noBorder();b.setPaddingTop(20);b.addElement(new Paragraph("______________________________\nDecisión: "+str(h.get("decision_by"),""),small));sign.addCell(b);doc.add(sign);
            doc.close();return new ReportFile(out.toByteArray(),"application/pdf",str(h.get("inspection_number"),"control_calidad")+".pdf");
        }catch(Exception e){throw new IllegalStateException("No se pudo generar el PDF de control de calidad",e);}
    }

    public ReportFile purchaseOrderPdf(UUID id){
        Map<String,Object> dto=purchaseOrders.detail(id);
        Map<String,Object> h=castMap(dto.get("header"));
        List<Map<String,Object>> items=castList(dto.get("items"));
        Map<String,Object> company=company();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Document doc=new Document(PageSize.A4,36,36,36,36); PdfWriter.getInstance(doc,out); doc.open();
            Font title=new Font(Font.HELVETICA,16,Font.BOLD); Font h2=new Font(Font.HELVETICA,10,Font.BOLD); Font small=new Font(Font.HELVETICA,8,Font.NORMAL); Font normal=new Font(Font.HELVETICA,9,Font.NORMAL);
            doc.add(documentHeader(company,"ORDEN DE COMPRA",str(h.get("order_number"),""),"Estado: "+statusEs(str(h.get("status"),"")),title,h2,small));doc.add(Chunk.NEWLINE);

            PdfPTable info=new PdfPTable(new float[]{1f,2.2f,1f,2.2f});info.setWidthPercentage(100);
            label(info,"Proveedor",h2);value(info,str(h.get("supplier_name"),"Pendiente"),normal);label(info,"RUC",h2);value(info,str(h.get("supplier_ruc"),"—"),normal);
            label(info,"Contacto",h2);value(info,str(h.get("supplier_contact"),"—"),normal);label(info,"Teléfono",h2);value(info,str(h.get("supplier_phone"),"—"),normal);
            label(info,"Correo",h2);value(info,str(h.get("supplier_email"),"—"),normal);label(info,"Dirección",h2);value(info,str(h.get("supplier_address"),"—"),normal);
            label(info,"Fecha",h2);value(info,fmtDate(h.get("order_date")),normal);label(info,"Entrega",h2);value(info,fmtDate(h.get("expected_date")),normal);
            label(info,"Moneda",h2);value(info,str(h.get("currency"),"PEN"),normal);label(info,"Condición pago",h2);value(info,str(h.get("payment_terms"),"—"),normal);
            label(info,"Origen",h2);value(info,str(h.get("request_number"),"Reposición / manual"),normal);label(info,"Lugar entrega",h2);value(info,str(h.get("delivery_address"),"—"),normal);
            doc.add(info);doc.add(Chunk.NEWLINE);

            PdfPTable table=new PdfPTable(new float[]{.55f,1.1f,3.5f,1f,1f,1.2f,1.3f});table.setWidthPercentage(100);
            for(String x:List.of("#","Código","Descripción","Cant.","Und.","P. Unit.","Importe"))header(table,x,h2);
            int line=1;for(Map<String,Object> it:items){cell(table,String.valueOf(line++),small);cell(table,str(it.get("item_code"),""),small);cell(table,str(it.get("item_name"),""),small);cellRight(table,num(it.get("quantity")),small);cell(table,str(it.get("unit"),""),small);cellRight(table,money(it.get("unit_price")),small);cellRight(table,money(it.get("line_total")),small);}doc.add(table);doc.add(Chunk.NEWLINE);

            PdfPTable totals=new PdfPTable(new float[]{4f,1.3f,1.4f});totals.setWidthPercentage(100);
            totals.addCell(noBorder()); label(totals,"Subtotal",h2);valueRight(totals,money(h.get("subtotal")),normal);
            totals.addCell(noBorder()); label(totals,"Descuento",h2);valueRight(totals,money(h.get("discount")),normal);
            totals.addCell(noBorder()); label(totals,"IGV "+num(h.get("igv_rate"))+"%",h2);valueRight(totals,money(h.get("igv_amount")),normal);
            totals.addCell(noBorder()); label(totals,"TOTAL "+str(h.get("currency"),""),h2);valueRight(totals,money(h.get("total")),new Font(Font.HELVETICA,11,Font.BOLD));
            doc.add(totals);doc.add(Chunk.NEWLINE);
            if(clean(h.get("notes"))!=null){doc.add(new Paragraph("Observaciones",h2));doc.add(new Paragraph(str(h.get("notes"),""),normal));doc.add(Chunk.NEWLINE);}
            PdfPTable sign=new PdfPTable(2);sign.setWidthPercentage(100);PdfPCell a=noBorder();a.setPaddingTop(24);a.addElement(new Paragraph("______________________________\nSolicitado / Compras",small));sign.addCell(a);PdfPCell b=noBorder();b.setPaddingTop(24);b.addElement(new Paragraph("______________________________\nAprobado",small));sign.addCell(b);doc.add(sign);
            doc.close();return new ReportFile(out.toByteArray(),"application/pdf",str(h.get("order_number"),"orden_compra")+".pdf");
        }catch(Exception e){throw new IllegalStateException("No se pudo generar el PDF de la orden de compra",e);}
    }


    public ReportFile dispatchList(String format,String status,LocalDate from,LocalDate to){
        StringBuilder sql=new StringBuilder("""
            select d.dispatch_number,d.dispatch_date,d.status,d.transport_mode,c.document_number client_document,c.name client_name,
                   so.order_number,po.production_number,po.batch_number,d.total_rolls,d.total_weight_kg,d.total_meters,
                   d.carrier_name,d.driver_name,d.vehicle_plate,d.guide_number,d.actual_dispatch_at,d.recipient_name,d.delivered_at,d.created_by
              from dispatches d join clients c on c.id=d.client_id join sales_orders so on so.id=d.sales_order_id
              join production_orders po on po.id=d.production_order_id where 1=1
            """);
        List<Object> args=new ArrayList<>();
        String st=status==null?null:status.trim().toUpperCase(Locale.ROOT);
        if(st!=null&&!st.isBlank()){sql.append(" and d.status=?");args.add(st);}
        if(from!=null){sql.append(" and d.dispatch_date>=?");args.add(from);}
        if(to!=null){sql.append(" and d.dispatch_date<=?");args.add(to);}
        sql.append(" order by d.dispatch_date desc,d.created_at desc");
        List<Map<String,Object>> rows=jdbc.queryForList(sql.toString(),args.toArray());
        List<Col> cols=List.of(new Col("Despacho","dispatch_number"),new Col("Fecha","dispatch_date"),new Col("Estado","status"),new Col("Transporte","transport_mode"),
                new Col("Documento cliente","client_document"),new Col("Cliente","client_name"),new Col("Pedido","order_number"),new Col("OP","production_number"),new Col("Lote","batch_number"),
                new Col("Rollos/Piezas","total_rolls"),new Col("Kg","total_weight_kg"),new Col("Metros","total_meters"),new Col("Transportista","carrier_name"),new Col("Chofer","driver_name"),
                new Col("Placa","vehicle_plate"),new Col("Guía","guide_number"),new Col("Salida","actual_dispatch_at"),new Col("Recibió","recipient_name"),new Col("Entrega","delivered_at"),new Col("Creado por","created_by"));
        return tabular("Despachos y entregas",rows,cols,"despachos",format);
    }

    public ReportFile dispatchPdf(UUID id){
        List<Map<String,Object>> headers=jdbc.queryForList("""
            select d.*,po.production_number,po.batch_number,po.actual_output_weight_kg,so.order_number,
                   c.document_number client_document,c.name client_name,c.address client_address,c.whatsapp client_whatsapp,c.email client_email,
                   f.name fabric_name,dc.name color_name,pt.name process_name,m.code machine_code,m.name machine_name
              from dispatches d join production_orders po on po.id=d.production_order_id join sales_orders so on so.id=d.sales_order_id
              join clients c on c.id=d.client_id join formulas fo on fo.id=po.formula_id join fabrics f on f.id=fo.fabric_id
              join dye_colors dc on dc.id=fo.color_id join process_types pt on pt.id=fo.process_id join machines m on m.id=po.machine_id
             where d.id=?
            """,id);
        if(headers.isEmpty())throw new IllegalArgumentException("El despacho no existe");
        Map<String,Object> h=headers.get(0),company=company();
        List<Map<String,Object>> rolls=jdbc.queryForList("select line_no,roll_code,weight_kg,meters,pieces,notes from dispatch_rolls where dispatch_id=? order by line_no",id);
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Document doc=new Document(PageSize.A4,32,32,34,34);PdfWriter.getInstance(doc,out);doc.open();
            Font title=new Font(Font.HELVETICA,15,Font.BOLD),h2=new Font(Font.HELVETICA,9,Font.BOLD),normal=new Font(Font.HELVETICA,8,Font.NORMAL),small=new Font(Font.HELVETICA,7,Font.NORMAL);
            doc.add(documentHeader(company,"CONSTANCIA DE DESPACHO",str(h.get("dispatch_number"),""),"Estado: "+dispatchStatusEs(str(h.get("status"),"DRAFT")),title,h2,small));doc.add(Chunk.NEWLINE);
            PdfPTable info=new PdfPTable(new float[]{1f,2f,1f,2f});info.setWidthPercentage(100);
            label(info,"Cliente",h2);value(info,str(h.get("client_name"),"—"),normal);label(info,"Documento",h2);value(info,str(h.get("client_document"),"—"),normal);
            label(info,"Pedido",h2);value(info,str(h.get("order_number"),"—"),normal);label(info,"OP / Lote",h2);value(info,str(h.get("production_number"),"—")+" / "+str(h.get("batch_number"),"—"),normal);
            label(info,"Tela",h2);value(info,str(h.get("fabric_name"),"—"),normal);label(info,"Color",h2);value(info,str(h.get("color_name"),"—"),normal);
            label(info,"Proceso",h2);value(info,str(h.get("process_name"),"—"),normal);label(info,"Fecha despacho",h2);value(info,fmtDate(h.get("dispatch_date")),normal);
            label(info,"Destino",h2);value(info,str(h.get("destination_address"),str(h.get("client_address"),"—")),normal);label(info,"Modo transporte",h2);value(info,transportModeEs(str(h.get("transport_mode"),"OWN")),normal);
            label(info,"Transportista",h2);value(info,str(h.get("carrier_name"),"—"),normal);label(info,"Chofer",h2);value(info,str(h.get("driver_name"),"—"),normal);
            label(info,"DNI chofer",h2);value(info,str(h.get("driver_document"),"—"),normal);label(info,"Placa",h2);value(info,str(h.get("vehicle_plate"),"—"),normal);
            label(info,"Guía / referencia",h2);value(info,str(h.get("guide_number"),"—"),normal);label(info,"Ref. cliente",h2);value(info,str(h.get("customer_reference"),"—"),normal);doc.add(info);doc.add(Chunk.NEWLINE);
            PdfPTable table=new PdfPTable(new float[]{.55f,1.6f,1.1f,1.1f,.8f,2.4f});table.setWidthPercentage(100);
            for(String x:List.of("#","Rollo / pieza","Peso kg","Metros","Piezas","Observación"))header(table,x,h2);
            for(Map<String,Object> x:rolls){cell(table,str(x.get("line_no"),""),small);cell(table,str(x.get("roll_code"),""),small);cellRight(table,num(x.get("weight_kg")),small);cellRight(table,num(x.get("meters")),small);cellRight(table,str(x.get("pieces"),"1"),small);cell(table,str(x.get("notes"),""),small);}doc.add(table);doc.add(Chunk.NEWLINE);
            PdfPTable totals=new PdfPTable(new float[]{3f,1.4f,1.4f});totals.setWidthPercentage(100);totals.addCell(noBorder());label(totals,"Total kg",h2);valueRight(totals,num(h.get("total_weight_kg")),normal);totals.addCell(noBorder());label(totals,"Total metros",h2);valueRight(totals,num(h.get("total_meters")),normal);totals.addCell(noBorder());label(totals,"Rollos / piezas",h2);valueRight(totals,str(h.get("total_rolls"),"0"),normal);doc.add(totals);
            if(clean(h.get("notes"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Observaciones de despacho",h2));doc.add(new Paragraph(str(h.get("notes"),""),normal));}
            if(clean(h.get("recipient_name"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Constancia de entrega",h2));doc.add(new Paragraph("Recibió: "+str(h.get("recipient_name"),"")+" · Documento: "+str(h.get("recipient_document"),"—")+" · Cargo: "+str(h.get("recipient_role"),"—"),normal));doc.add(new Paragraph("Fecha/hora: "+display(h.get("delivered_at")),normal));if(clean(h.get("delivery_notes"))!=null)doc.add(new Paragraph("Observación: "+h.get("delivery_notes"),normal));}
            doc.add(Chunk.NEWLINE);PdfPTable sign=new PdfPTable(2);sign.setWidthPercentage(100);PdfPCell a=noBorder();a.setPaddingTop(22);a.addElement(new Paragraph("______________________________\nEntrega / Despacho",small));sign.addCell(a);PdfPCell b=noBorder();b.setPaddingTop(22);b.addElement(new Paragraph("______________________________\nRecepción cliente",small));sign.addCell(b);doc.add(sign);
            doc.close();return new ReportFile(out.toByteArray(),"application/pdf",str(h.get("dispatch_number"),"despacho")+".pdf");
        }catch(Exception e){throw new IllegalStateException("No se pudo generar el PDF del despacho",e);}
    }

    public ReportFile customerReturns(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select cr.return_number,cr.return_date,cr.status,cr.reason_code,cr.decision,c.name client_name,
                   so.order_number,d.dispatch_number,po.production_number source_production_number,
                   cr.total_rolls,cr.total_weight_kg,cr.total_meters,cr.return_transport_cost,cr.other_cost,cr.credit_amount,
                   cr.received_by,cr.received_at,cr.decision_by,cr.decision_at,rpo.production_number reprocess_production_number,
                   rpo.status reprocess_status,cr.closed_by,cr.closed_at,cr.created_by,cr.created_at
              from customer_returns cr join clients c on c.id=cr.client_id join sales_orders so on so.id=cr.sales_order_id
              join dispatches d on d.id=cr.dispatch_id join production_orders po on po.id=cr.source_production_order_id
              left join production_orders rpo on rpo.id=cr.reprocess_production_order_id
             order by cr.created_at desc
            """);
        for(Map<String,Object> row:rows){
            row.put("status",returnStatusEs(str(row.get("status"),"")));
            row.put("reason_code",returnReasonEs(str(row.get("reason_code"),"")));
            row.put("decision",returnDecisionEs(str(row.get("decision"),"")));
        }
        List<Col> cols=List.of(new Col("Devolución","return_number"),new Col("Fecha","return_date"),new Col("Estado","status"),new Col("Motivo","reason_code"),
            new Col("Resolución","decision"),new Col("Cliente","client_name"),new Col("Pedido","order_number"),new Col("Despacho","dispatch_number"),
            new Col("OP origen","source_production_number"),new Col("Rollos/Piezas","total_rolls"),new Col("Kg devueltos","total_weight_kg"),new Col("Metros","total_meters"),
            new Col("Transporte devolución","return_transport_cost"),new Col("Otros costos","other_cost"),new Col("Crédito","credit_amount"),
            new Col("Recibido por","received_by"),new Col("Recepción","received_at"),new Col("Decidido por","decision_by"),new Col("Decisión","decision_at"),
            new Col("OP reproceso","reprocess_production_number"),new Col("Estado reproceso","reprocess_status"),new Col("Cerrado","closed_at"));
        return tabular("Devoluciones post-despacho",rows,cols,"devoluciones_cliente",format);
    }

    public ReportFile customerReturnPdf(UUID id){
        List<Map<String,Object>> headers=jdbc.queryForList("""
            select cr.*,d.dispatch_number,d.guide_number,d.delivered_at,po.production_number source_production_number,po.batch_number source_batch_number,
                   so.order_number,c.name client_name,c.document_number client_document,c.address client_address,
                   f.name fabric_name,dc.name color_name,pt.name process_name,
                   rpo.production_number reprocess_production_number,rpo.batch_number reprocess_batch_number,rpo.status reprocess_status
              from customer_returns cr join dispatches d on d.id=cr.dispatch_id join production_orders po on po.id=cr.source_production_order_id
              join sales_orders so on so.id=cr.sales_order_id join clients c on c.id=cr.client_id join formulas fo on fo.id=po.formula_id
              join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id join process_types pt on pt.id=fo.process_id
              left join production_orders rpo on rpo.id=cr.reprocess_production_order_id where cr.id=?
            """,id);
        if(headers.isEmpty())throw new IllegalArgumentException("La devolución no existe");
        Map<String,Object> h=headers.get(0),company=company();
        List<Map<String,Object>> lines=jdbc.queryForList("select line_no,roll_code,dispatched_weight_kg,returned_weight_kg,returned_meters,pieces,condition_code,notes from customer_return_lines where customer_return_id=? order by line_no",id);
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Document doc=new Document(PageSize.A4,32,32,34,34);PdfWriter.getInstance(doc,out);doc.open();
            Font title=new Font(Font.HELVETICA,15,Font.BOLD),h2=new Font(Font.HELVETICA,9,Font.BOLD),normal=new Font(Font.HELVETICA,8,Font.NORMAL),small=new Font(Font.HELVETICA,7,Font.NORMAL);
            doc.add(documentHeader(company,"DEVOLUCIÓN DE CLIENTE",str(h.get("return_number"),""),"Estado: "+returnStatusEs(str(h.get("status"),"")),title,h2,small));doc.add(Chunk.NEWLINE);
            PdfPTable info=new PdfPTable(new float[]{1f,2f,1f,2f});info.setWidthPercentage(100);
            label(info,"Cliente",h2);value(info,str(h.get("client_name"),"—"),normal);label(info,"Documento",h2);value(info,str(h.get("client_document"),"—"),normal);
            label(info,"Pedido",h2);value(info,str(h.get("order_number"),"—"),normal);label(info,"Despacho",h2);value(info,str(h.get("dispatch_number"),"—"),normal);
            label(info,"OP origen",h2);value(info,str(h.get("source_production_number"),"—")+" / "+str(h.get("source_batch_number"),"—"),normal);label(info,"Fecha devolución",h2);value(info,fmtDate(h.get("return_date")),normal);
            label(info,"Tela / color",h2);value(info,str(h.get("fabric_name"),"—")+" / "+str(h.get("color_name"),"—"),normal);label(info,"Proceso",h2);value(info,str(h.get("process_name"),"—"),normal);
            label(info,"Motivo",h2);value(info,returnReasonEs(str(h.get("reason_code"),"OTHER")),normal);label(info,"Resolución",h2);value(info,returnDecisionEs(str(h.get("decision"),"PENDING")),normal);doc.add(info);doc.add(Chunk.NEWLINE);
            PdfPTable table=new PdfPTable(new float[]{.45f,1.45f,1f,1f,1f,.7f,1.2f,2f});table.setWidthPercentage(100);
            for(String x:List.of("#","Rollo / pieza","Kg despacho","Kg devueltos","Metros","Piezas","Condición","Observación"))header(table,x,h2);
            for(Map<String,Object> x:lines){cell(table,str(x.get("line_no"),""),small);cell(table,str(x.get("roll_code"),""),small);cellRight(table,num(x.get("dispatched_weight_kg")),small);cellRight(table,num(x.get("returned_weight_kg")),small);cellRight(table,num(x.get("returned_meters")),small);cellRight(table,str(x.get("pieces"),"1"),small);cell(table,returnReasonEs(str(x.get("condition_code"),"OTHER")),small);cell(table,str(x.get("notes"),""),small);}doc.add(table);doc.add(Chunk.NEWLINE);
            PdfPTable totals=new PdfPTable(new float[]{3f,1.5f,1.5f});totals.setWidthPercentage(100);totals.addCell(noBorder());label(totals,"Total kg",h2);valueRight(totals,num(h.get("total_weight_kg")),normal);totals.addCell(noBorder());label(totals,"Rollos / piezas",h2);valueRight(totals,str(h.get("total_rolls"),"0"),normal);totals.addCell(noBorder());label(totals,"Costo retorno",h2);valueRight(totals,"S/ "+money(h.get("return_transport_cost")),normal);totals.addCell(noBorder());label(totals,"Otros costos",h2);valueRight(totals,"S/ "+money(h.get("other_cost")),normal);doc.add(totals);
            if(clean(h.get("description"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Reclamo / descripción",h2));doc.add(new Paragraph(str(h.get("description"),""),normal));}
            if(clean(h.get("inspection_notes"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Inspección",h2));doc.add(new Paragraph(str(h.get("inspection_notes"),""),normal));}
            if(clean(h.get("decision_notes"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Decisión",h2));doc.add(new Paragraph(str(h.get("decision_notes"),""),normal));}
            if(clean(h.get("reprocess_production_number"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Reproceso: "+h.get("reprocess_production_number")+" · "+str(h.get("reprocess_status"),""),h2));}
            doc.add(Chunk.NEWLINE);PdfPTable sign=new PdfPTable(2);sign.setWidthPercentage(100);PdfPCell a=noBorder();a.setPaddingTop(22);a.addElement(new Paragraph("______________________________\nRecepción de devolución",small));sign.addCell(a);PdfPCell b=noBorder();b.setPaddingTop(22);b.addElement(new Paragraph("______________________________\nControl / resolución",small));sign.addCell(b);doc.add(sign);
            doc.close();return new ReportFile(out.toByteArray(),"application/pdf",str(h.get("return_number"),"devolucion")+".pdf");
        }catch(Exception e){throw new IllegalStateException("No se pudo generar el PDF de la devolución",e);}
    }

    public ReportFile costingOrders(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pcs.snapshot_number,pcs.revision_no,pcs.calculated_at,pcs.calculated_by,po.production_number,po.batch_number,
                   c.name client_name,so.order_number,m.code machine_code,m.name machine_name,po.status,
                   pcs.sale_currency,pcs.exchange_rate,pcs.allocated_sale_amount_source,pcs.allocated_sale_amount_base,
                   pcs.material_actual_cost,pcs.water_cost,pcs.energy_cost,pcs.machine_cost,pcs.labor_cost,pcs.overhead_cost,pcs.other_cost,
                   pcs.total_cost,pcs.actual_output_weight_kg,pcs.cost_per_output_kg,pcs.gross_margin,pcs.margin_pct,pcs.unvalued_material_qty
              from production_cost_snapshots pcs join production_orders po on po.id=pcs.production_order_id
              join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id join machines m on m.id=po.machine_id
             where pcs.current_snapshot=true order by pcs.calculated_at desc
            """);
        List<Col> cols=List.of(new Col("Costeo","snapshot_number"),new Col("Rev.","revision_no"),new Col("Fecha","calculated_at"),new Col("Usuario","calculated_by"),
            new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),new Col("Pedido","order_number"),new Col("Máquina","machine_code"),
            new Col("Estado OP","status"),new Col("Moneda venta","sale_currency"),new Col("TC","exchange_rate"),new Col("Venta asignada origen","allocated_sale_amount_source"),
            new Col("Venta asignada PEN","allocated_sale_amount_base"),new Col("Materiales","material_actual_cost"),new Col("Agua","water_cost"),new Col("Energía","energy_cost"),
            new Col("Máquina","machine_cost"),new Col("Mano obra","labor_cost"),new Col("Indirectos","overhead_cost"),new Col("Otros","other_cost"),new Col("Costo total","total_cost"),
            new Col("Kg producidos","actual_output_weight_kg"),new Col("Costo/kg","cost_per_output_kg"),new Col("Margen","gross_margin"),new Col("Margen %","margin_pct"),new Col("Kg sin valorizar","unvalued_material_qty"));
        return tabular("Costeo industrial por orden de producción",rows,cols,"costeo_industrial_op",format);
    }

    public ReportFile costingMaterials(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pcs.snapshot_number,pcs.calculated_at,po.production_number,po.batch_number,c.name client_name,
                   pcm.item_code,pcm.item_name,pcm.planned_quantity_kg,pcm.actual_quantity_kg,pcm.valued_actual_quantity_kg,
                   pcm.unvalued_actual_quantity_kg,pcm.unit_cost,pcm.planned_cost,pcm.actual_cost,pcm.cost_source
              from production_cost_materials pcm join production_cost_snapshots pcs on pcs.id=pcm.production_cost_snapshot_id
              join production_orders po on po.id=pcs.production_order_id join clients c on c.id=po.client_id
             where pcs.current_snapshot=true order by pcs.calculated_at desc,po.production_number,pcm.item_name
            """);
        List<Col> cols=List.of(new Col("Costeo","snapshot_number"),new Col("Fecha","calculated_at"),new Col("OP","production_number"),new Col("Lote","batch_number"),new Col("Cliente","client_name"),
            new Col("Código","item_code"),new Col("Insumo","item_name"),new Col("Teórico kg","planned_quantity_kg"),new Col("Real kg","actual_quantity_kg"),new Col("Kg valorizados","valued_actual_quantity_kg"),
            new Col("Kg sin valorizar","unvalued_actual_quantity_kg"),new Col("Costo unitario","unit_cost"),new Col("Costo teórico","planned_cost"),new Col("Costo real","actual_cost"),new Col("Fuente costo","cost_source"));
        return tabular("Costos de insumos por orden de producción",rows,cols,"costeo_materiales",format);
    }

    public ReportFile costingOrderPdf(UUID orderId){
        Map<String,Object> h=oneMap("""
            select pcs.*,po.production_number,po.batch_number,po.status,po.operator_name,po.actual_start,po.actual_end,
                   c.document_number client_document,c.name client_name,so.order_number,m.code machine_code,m.name machine_name,
                   f.name fabric_name,dc.name color_name,pt.name process_name
              from production_cost_snapshots pcs join production_orders po on po.id=pcs.production_order_id
              join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id join sales_order_items soi on soi.id=po.sales_order_item_id
              join machines m on m.id=po.machine_id join fabrics f on f.id=soi.fabric_id join dye_colors dc on dc.id=soi.color_id join process_types pt on pt.id=soi.process_id
             where pcs.production_order_id=? and pcs.current_snapshot=true
            """,orderId);
        if(h==null)throw new IllegalArgumentException("La OP todavía no tiene un costeo calculado");
        List<Map<String,Object>> mats=jdbc.queryForList("select item_code,item_name,planned_quantity_kg,actual_quantity_kg,unit_cost,planned_cost,actual_cost,unvalued_actual_quantity_kg,cost_source from production_cost_materials where production_cost_snapshot_id=? order by item_name",h.get("id"));
        Map<String,Object> co=company();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Document doc=new Document(PageSize.A4,32,32,30,30);PdfWriter.getInstance(doc,out);doc.open();Font title=new Font(Font.HELVETICA,15,Font.BOLD);Font h2=new Font(Font.HELVETICA,9,Font.BOLD);Font normal=new Font(Font.HELVETICA,8);Font small=new Font(Font.HELVETICA,7);
            doc.add(documentHeader(co,"FICHA DE COSTEO INDUSTRIAL",str(h.get("snapshot_number"),""),"Revisión "+str(h.get("revision_no"),"1"),title,h2,small));doc.add(Chunk.NEWLINE);
            PdfPTable info=new PdfPTable(new float[]{1.1f,2.2f,1.1f,2.2f});info.setWidthPercentage(100);
            label(info,"OP / Lote",h2);value(info,str(h.get("production_number"),"—")+" / "+str(h.get("batch_number"),"—"),normal);label(info,"Pedido",h2);value(info,str(h.get("order_number"),"—"),normal);
            label(info,"Cliente",h2);value(info,str(h.get("client_name"),"—"),normal);label(info,"Máquina",h2);value(info,str(h.get("machine_code"),"—")+" · "+str(h.get("machine_name"),""),normal);
            label(info,"Tela / color",h2);value(info,str(h.get("fabric_name"),"—")+" / "+str(h.get("color_name"),"—"),normal);label(info,"Proceso",h2);value(info,str(h.get("process_name"),"—"),normal);
            label(info,"Kg producidos",h2);value(info,num(h.get("actual_output_weight_kg")),normal);label(info,"Merma / reproceso",h2);value(info,num(h.get("waste_weight_kg"))+" / "+num(h.get("reprocess_weight_kg"))+" kg",normal);doc.add(info);doc.add(Chunk.NEWLINE);
            PdfPTable table=new PdfPTable(new float[]{1.1f,2.4f,1f,1f,1f,1.1f,1.1f});table.setWidthPercentage(100);for(String x:List.of("Código","Insumo","Teórico kg","Real kg","C.Unit PEN","C.Teórico","C.Real"))header(table,x,h2);
            for(Map<String,Object>x:mats){cell(table,str(x.get("item_code"),""),small);cell(table,str(x.get("item_name"),""),small);cellRight(table,num(x.get("planned_quantity_kg")),small);cellRight(table,num(x.get("actual_quantity_kg")),small);cellRight(table,money(x.get("unit_cost")),small);cellRight(table,money(x.get("planned_cost")),small);cellRight(table,money(x.get("actual_cost")),small);}doc.add(table);doc.add(Chunk.NEWLINE);
            PdfPTable costs=new PdfPTable(new float[]{2.8f,1.2f});costs.setWidthPercentage(72);costs.setHorizontalAlignment(Element.ALIGN_RIGHT);
            String[][] lines={{"Materiales",money(h.get("material_actual_cost"))},{"Agua",money(h.get("water_cost"))},{"Energía",money(h.get("energy_cost"))},{"Máquina",money(h.get("machine_cost"))},{"Mano de obra",money(h.get("labor_cost"))},{"Costos indirectos",money(h.get("overhead_cost"))},{"Otros costos",money(h.get("other_cost"))},{"COSTO TOTAL",money(h.get("total_cost"))},{"Venta asignada (PEN)",money(h.get("allocated_sale_amount_base"))},{"MARGEN",money(h.get("gross_margin"))},{"Margen %",num(h.get("margin_pct"))+" %"},{"Costo / kg",money(h.get("cost_per_output_kg"))}};
            for(String[]x:lines){label(costs,x[0],h2);valueRight(costs,x[1],normal);}doc.add(costs);
            BigDecimal unvalued=h.get("unvalued_material_qty")==null?BigDecimal.ZERO:new BigDecimal(h.get("unvalued_material_qty").toString());if(unvalued.compareTo(BigDecimal.ZERO)>0){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("ADVERTENCIA: existen "+num(unvalued)+" kg de insumos sin costo valorizado. Revise costo estándar o costos de recepción antes de considerar el margen como definitivo.",h2));}
            if(clean(h.get("costing_notes"))!=null){doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Notas de costeo",h2));doc.add(new Paragraph(str(h.get("costing_notes"),""),normal));}
            doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Calculado por: "+str(h.get("calculated_by"),"—")+" · "+display(h.get("calculated_at")),small));doc.close();return new ReportFile(out.toByteArray(),"application/pdf",str(h.get("snapshot_number"),"costeo")+"_"+str(h.get("production_number"),"OP")+".pdf");
        }catch(Exception e){throw new IllegalStateException("No se pudo generar la ficha de costeo",e);}
    }

    public ReportFile executiveDashboard(String format,LocalDate from,LocalDate to){
        LocalDate[] p=reportPeriod(from,to);from=p[0];to=p[1];
        List<Map<String,Object>> rows=new ArrayList<>();
        Map<String,Object> commercial=oneMap("""
            select count(*) filter(where created_at::date between ? and ?) orders_created,
                   count(*) filter(where status not in ('DELIVERED','CANCELLED')) open_orders,
                   count(*) filter(where requested_date < current_date and status not in ('DELIVERED','CANCELLED')) overdue_orders
              from sales_orders
            """,from,to);
        Map<String,Object> production=oneMap("""
            select coalesce(sum(actual_output_weight_kg) filter(where actual_end::date between ? and ?),0) produced_kg,
                   coalesce(sum(waste_weight_kg) filter(where actual_end::date between ? and ?),0) waste_kg,
                   count(*) filter(where status='IN_PROCESS') in_process,
                   count(*) filter(where status='REPROCESS_REQUIRED') reprocess_required
              from production_orders
            """,from,to,from,to);
        Map<String,Object> quality=oneMap("""
            select count(*) filter(where decision_at::date between ? and ? and status='DECIDED') decided,
                   count(*) filter(where decision_at::date between ? and ? and result='APPROVED') approved,
                   count(*) filter(where decision_at::date between ? and ? and result='REPROCESS') reprocess,
                   count(*) filter(where decision_at::date between ? and ? and result='REJECTED') rejected
              from quality_inspections
            """,from,to,from,to,from,to,from,to);
        Map<String,Object> dispatch=oneMap("""
            select count(*) filter(where status='DISPATCHED') awaiting_delivery,
                   count(*) filter(where delivered_at::date between ? and ? and status='DELIVERED') deliveries,
                   coalesce(sum(total_weight_kg) filter(where delivered_at::date between ? and ? and status='DELIVERED'),0) delivered_kg
              from dispatches
            """,from,to,from,to);
        Map<String,Object> stock=oneMap("""
            with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
                 reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
            select count(*) filter(where coalesce(p.qty,0)=0) no_stock,
                   count(*) filter(where coalesce(p.qty,0)>0 and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)<=i.minimum_stock) low_stock
              from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id where i.active=true
            """);
        Map<String,Object> finance=oneMap("""
            select count(*) costed_orders,coalesce(sum(allocated_sale_amount_base),0) sale_pen,
                   coalesce(sum(total_cost),0) cost_pen,coalesce(sum(gross_margin),0) margin_pen,
                   coalesce(sum(unvalued_material_qty),0) unvalued_kg
              from production_cost_snapshots where current_snapshot=true and calculated_at::date between ? and ?
            """,from,to);
        BigDecimal sale=finance==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(finance.getOrDefault("sale_pen",0)));
        BigDecimal margin=finance==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(finance.getOrDefault("margin_pen",0)));
        BigDecimal marginPct=sale.signum()==0?BigDecimal.ZERO:margin.multiply(BigDecimal.valueOf(100)).divide(sale,2,java.math.RoundingMode.HALF_UP);
        addMetric(rows,"Periodo","Desde",from.toString());addMetric(rows,"Periodo","Hasta",to.toString());
        addMetric(rows,"Comercial","Pedidos creados",commercial.get("orders_created"));addMetric(rows,"Comercial","Pedidos abiertos",commercial.get("open_orders"));addMetric(rows,"Comercial","Pedidos vencidos",commercial.get("overdue_orders"));
        addMetric(rows,"Producción","Kg producidos",production.get("produced_kg"));addMetric(rows,"Producción","Kg merma",production.get("waste_kg"));addMetric(rows,"Producción","OP en proceso",production.get("in_process"));addMetric(rows,"Producción","OP en reproceso",production.get("reprocess_required"));
        addMetric(rows,"Calidad","Inspecciones decididas",quality.get("decided"));addMetric(rows,"Calidad","Aprobadas",quality.get("approved"));addMetric(rows,"Calidad","Reprocesos",quality.get("reprocess"));addMetric(rows,"Calidad","Rechazadas",quality.get("rejected"));
        addMetric(rows,"Despacho","Entregas",dispatch.get("deliveries"));addMetric(rows,"Despacho","Kg entregados",dispatch.get("delivered_kg"));addMetric(rows,"Despacho","Pendientes de confirmar entrega",dispatch.get("awaiting_delivery"));
        addMetric(rows,"Almacén","Sin stock",stock.get("no_stock"));addMetric(rows,"Almacén","Stock bajo",stock.get("low_stock"));
        addMetric(rows,"Rentabilidad","OP costeadas",finance.get("costed_orders"));addMetric(rows,"Rentabilidad","Venta asignada PEN",finance.get("sale_pen"));addMetric(rows,"Rentabilidad","Costo real PEN",finance.get("cost_pen"));addMetric(rows,"Rentabilidad","Margen PEN",finance.get("margin_pen"));addMetric(rows,"Rentabilidad","Margen %",marginPct);addMetric(rows,"Rentabilidad","Kg sin valorizar",finance.get("unvalued_kg"));
        return tabular("Resumen gerencial "+from+" a "+to,rows,List.of(new Col("Área","area"),new Col("Indicador","indicator"),new Col("Valor","value")),"resumen_gerencial",format);
    }

    public ReportFile commercialQuotations(String format,LocalDate from,LocalDate to){
        LocalDate[] p=reportPeriod(from,to);from=p[0];to=p[1];
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select q.quote_number,c.document_number client_document,c.name client_name,q.status,q.currency,q.valid_until,q.requested_date,
                   q.subtotal,q.discount_amount,q.tax_amount,q.total,q.created_by,q.created_at,q.sent_at,q.approved_at
              from quotations q join clients c on c.id=q.client_id
             where q.created_at::date between ? and ? order by q.created_at desc
            """,from,to);
        List<Col> cols=List.of(new Col("Cotización","quote_number"),new Col("Documento","client_document"),new Col("Cliente","client_name"),new Col("Estado","status"),new Col("Moneda","currency"),new Col("Vigencia","valid_until"),new Col("Fecha requerida","requested_date"),new Col("Subtotal","subtotal"),new Col("Descuento","discount_amount"),new Col("Impuesto","tax_amount"),new Col("Total","total"),new Col("Creado por","created_by"),new Col("Creado","created_at"),new Col("Enviado","sent_at"),new Col("Aprobado","approved_at"));
        return tabular("Cotizaciones / proformas",rows,cols,"cotizaciones",format);
    }

    public ReportFile commercialOrders(String format,LocalDate from,LocalDate to){
        LocalDate[] p=reportPeriod(from,to);from=p[0];to=p[1];
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select so.order_number,q.quote_number,c.document_number client_document,c.name client_name,so.status,so.priority,
                   so.requested_date,so.currency,so.total,so.created_by,so.created_at,so.confirmed_at
              from sales_orders so join clients c on c.id=so.client_id join quotations q on q.id=so.quotation_id
             where so.created_at::date between ? and ? order by so.created_at desc
            """,from,to);
        List<Col> cols=List.of(new Col("Pedido","order_number"),new Col("Proforma","quote_number"),new Col("Documento","client_document"),new Col("Cliente","client_name"),new Col("Estado","status"),new Col("Prioridad","priority"),new Col("Fecha requerida","requested_date"),new Col("Moneda","currency"),new Col("Total","total"),new Col("Creado por","created_by"),new Col("Creado","created_at"),new Col("Confirmado","confirmed_at"));
        return tabular("Pedidos comerciales",rows,cols,"pedidos",format);
    }

    public ReportFile fabricReceptions(String format,LocalDate from,LocalDate to){
        LocalDate[] p=reportPeriod(from,to);from=p[0];to=p[1];
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select fr.reception_number,so.order_number,c.name client_name,fr.status,fr.received_date,fr.client_guide_number,
                   fr.vehicle_plate,fr.delivered_by,fr.received_by,fr.warehouse_location,fr.roll_count,fr.total_declared_kg,
                   fr.total_net_kg,fr.total_meters,fr.created_by,fr.released_at
              from fabric_receptions fr join sales_orders so on so.id=fr.sales_order_id join clients c on c.id=fr.client_id
             where fr.received_date between ? and ? order by fr.received_date desc,fr.reception_number desc
            """,from,to);
        List<Col> cols=List.of(new Col("Recepción","reception_number"),new Col("Pedido","order_number"),new Col("Cliente","client_name"),new Col("Estado","status"),new Col("Fecha","received_date"),new Col("Guía cliente","client_guide_number"),new Col("Placa","vehicle_plate"),new Col("Entregó","delivered_by"),new Col("Recibió","received_by"),new Col("Ubicación","warehouse_location"),new Col("Rollos","roll_count"),new Col("Kg declarados","total_declared_kg"),new Col("Kg netos","total_net_kg"),new Col("Metros","total_meters"),new Col("Creado por","created_by"),new Col("Liberado","released_at"));
        return tabular("Recepciones de tela",rows,cols,"recepciones_tela",format);
    }

    public ReportFile laboratoryRequests(String format,LocalDate from,LocalDate to){
        LocalDate[] p=reportPeriod(from,to);from=p[0];to=p[1];
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select lr.lab_number,so.order_number,c.name client_name,f.name fabric_name,dc.name color_name,pt.name process_name,
                   lr.status,lr.reference_method,lr.color_standard,lr.actual_weight_kg,lr.selected_alternative,lr.created_by,
                   lr.created_at,lr.sent_at,lr.approved_at,lr.rejected_at
              from lab_requests lr join sales_orders so on so.id=lr.sales_order_id join clients c on c.id=lr.client_id
              join fabrics f on f.id=lr.fabric_id join dye_colors dc on dc.id=lr.color_id join process_types pt on pt.id=lr.process_id
             where lr.created_at::date between ? and ? order by lr.created_at desc
            """,from,to);
        List<Col> cols=List.of(new Col("Laboratorio","lab_number"),new Col("Pedido","order_number"),new Col("Cliente","client_name"),new Col("Tela","fabric_name"),new Col("Color","color_name"),new Col("Proceso","process_name"),new Col("Estado","status"),new Col("Método","reference_method"),new Col("Estándar","color_standard"),new Col("Kg reales","actual_weight_kg"),new Col("Alternativa","selected_alternative"),new Col("Creado por","created_by"),new Col("Creado","created_at"),new Col("Enviado","sent_at"),new Col("Aprobado","approved_at"),new Col("Rechazado","rejected_at"));
        return tabular("Laboratorio de color",rows,cols,"laboratorio_color",format);
    }

    public ReportFile clients(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select document_number,name,contact_name,whatsapp,email,address,credit_days,active,created_at,updated_at
              from clients order by lower(name)
            """);
        List<Col> cols=List.of(new Col("Documento","document_number"),new Col("Cliente","name"),new Col("Contacto","contact_name"),new Col("WhatsApp","whatsapp"),new Col("Correo","email"),new Col("Dirección","address"),new Col("Crédito días","credit_days"),new Col("Activo","active"),new Col("Creado","created_at"),new Col("Actualizado","updated_at"));
        return tabular("Maestro de clientes",rows,cols,"clientes",format);
    }

    public ReportFile suppliers(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select ruc,name,contact_name,email,phone,address,payment_terms,bank_reference,active,created_at,updated_at
              from suppliers order by lower(name)
            """);
        List<Col> cols=List.of(new Col("RUC","ruc"),new Col("Proveedor","name"),new Col("Contacto","contact_name"),new Col("Correo","email"),new Col("Teléfono","phone"),new Col("Dirección","address"),new Col("Condición pago","payment_terms"),new Col("Referencia bancaria","bank_reference"),new Col("Activo","active"),new Col("Creado","created_at"),new Col("Actualizado","updated_at"));
        return tabular("Maestro de proveedores",rows,cols,"proveedores",format);
    }

    public ReportFile priceLists(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pl.code,pl.name,c.name client_name,pl.currency,pl.valid_from,pl.valid_to,pl.priority,pl.active,
                   count(pr.id) rule_count,min(pr.unit_price) min_price,max(pr.unit_price) max_price,pl.created_at,pl.updated_at
              from price_lists pl left join clients c on c.id=pl.client_id left join price_rules pr on pr.price_list_id=pl.id and pr.active=true
             group by pl.id,c.name order by pl.active desc,pl.priority,lower(pl.name)
            """);
        List<Col> cols=List.of(new Col("Código","code"),new Col("Lista","name"),new Col("Cliente","client_name"),new Col("Moneda","currency"),new Col("Desde","valid_from"),new Col("Hasta","valid_to"),new Col("Prioridad","priority"),new Col("Activo","active"),new Col("Reglas","rule_count"),new Col("Precio mín.","min_price"),new Col("Precio máx.","max_price"),new Col("Creado","created_at"),new Col("Actualizado","updated_at"));
        return tabular("Listas de precios",rows,cols,"listas_precios",format);
    }

    public ReportFile formulas(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select fo.code,fo.name,c.name client_name,f.name fabric_name,dc.name color_name,pt.name process_name,ft.name finish_name,
                   fo.current_version,fv.status version_status,fv.bath_ratio,fv.temperature_c,fv.time_minutes,fv.ph,
                   count(fc.id) component_count,fo.active,fo.updated_at
              from formulas fo left join clients c on c.id=fo.client_id join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id
              join process_types pt on pt.id=fo.process_id left join finish_types ft on ft.id=fo.finish_id
              left join formula_versions fv on fv.formula_id=fo.id and fv.version_no=fo.current_version
              left join formula_components fc on fc.version_id=fv.id
             group by fo.id,c.name,f.name,dc.name,pt.name,ft.name,fv.status,fv.bath_ratio,fv.temperature_c,fv.time_minutes,fv.ph
             order by lower(fo.code)
            """);
        List<Col> cols=List.of(new Col("Código","code"),new Col("Fórmula","name"),new Col("Cliente","client_name"),new Col("Tela","fabric_name"),new Col("Color","color_name"),new Col("Proceso","process_name"),new Col("Acabado","finish_name"),new Col("Versión","current_version"),new Col("Estado versión","version_status"),new Col("Relación baño","bath_ratio"),new Col("Temp. °C","temperature_c"),new Col("Tiempo min","time_minutes"),new Col("pH","ph"),new Col("Componentes","component_count"),new Col("Activo","active"),new Col("Actualizado","updated_at"));
        return tabular("Fórmulas y recetas maestras",rows,cols,"formulas",format);
    }

    public ReportFile masterData(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select * from (
              select 'TELA' type,code,name,active,updated_at from fabrics
              union all select 'COLOR',code,name,active,updated_at from dye_colors
              union all select 'PROCESO',code,name,active,updated_at from process_types
              union all select 'ACABADO',code,name,active,updated_at from finish_types
              union all select 'UNIDAD',code,name,active,updated_at from measurement_units
              union all select 'MÁQUINA',code,name,active,updated_at from machines
            ) x order by type,lower(name)
            """);
        List<Col> cols=List.of(new Col("Tipo","type"),new Col("Código","code"),new Col("Nombre","name"),new Col("Activo","active"),new Col("Actualizado","updated_at"));
        return tabular("Maestros técnicos",rows,cols,"maestros_tecnicos",format);
    }

    public ReportFile users(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select u.email,u.display_name,u.active,u.must_change_password,u.failed_login_attempts,u.locked_until,u.last_login_at,
                   coalesce(string_agg(distinct r.name,', ' order by r.name),'') roles,u.created_at,u.updated_at
              from app_users u left join user_roles ur on ur.user_id=u.id left join roles r on r.id=ur.role_id
             group by u.id order by lower(u.display_name)
            """);
        List<Col> cols=List.of(new Col("Correo","email"),new Col("Usuario","display_name"),new Col("Activo","active"),new Col("Cambio clave","must_change_password"),new Col("Intentos fallidos","failed_login_attempts"),new Col("Bloqueado hasta","locked_until"),new Col("Último acceso","last_login_at"),new Col("Roles","roles"),new Col("Creado","created_at"),new Col("Actualizado","updated_at"));
        return tabular("Usuarios y roles",rows,cols,"usuarios_roles",format);
    }

    public ReportFile auditEvents(String format,LocalDate from,LocalDate to){
        LocalDate[] p=reportPeriod(from,to);from=p[0];to=p[1];
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select created_at,actor,module,action,success,ip_address,http_method,request_path,reference_id,detail from audit_events
             where created_at::date between ? and ? order by created_at desc limit 10000
            """,from,to);
        List<Col> cols=List.of(new Col("Fecha","created_at"),new Col("Usuario","actor"),new Col("Módulo","module"),new Col("Acción","action"),new Col("Resultado","success"),new Col("IP","ip_address"),new Col("Método","http_method"),new Col("Ruta","request_path"),new Col("Referencia","reference_id"),new Col("Detalle","detail"));
        return tabular("Auditoría del ERP",rows,cols,"auditoria_erp",format);
    }

    public ReportFile pilotChecklist(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pc.code cycle_code,pc.name cycle_name,pci.sort_order,pci.area,pci.title,pci.description,pci.mandatory,pci.status,
                   pci.responsible,pci.evidence,pci.validated_by,pci.validated_at,pci.updated_by,pci.updated_at
              from pilot_checklist_items pci join pilot_cycles pc on pc.id=pci.cycle_id
             where pc.id=(select id from pilot_cycles order by case when status='CLOSED' then 1 else 0 end,created_at desc limit 1)
             order by pci.sort_order,pci.area,pci.title
            """);
        List<Col> cols=List.of(new Col("Área","area"),new Col("Punto de control","title"),new Col("Descripción","description"),new Col("Obligatorio","mandatory"),new Col("Estado","status"),new Col("Responsable","responsible"),new Col("Evidencia / notas","evidence"),new Col("Validado por","validated_by"),new Col("Validado","validated_at"),new Col("Actualizado por","updated_by"),new Col("Actualizado","updated_at"));
        return tabular("Checklist de puesta en marcha",rows,cols,"piloto_checklist",format);
    }

    public ReportFile pilotIncidents(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select pi.incident_number,pi.severity,pi.status,pi.module,pi.title,pi.description,pi.steps_to_reproduce,pi.expected_result,pi.actual_result,
                   pi.assigned_to,pi.resolution,pi.reported_by,pi.reported_at,pi.resolved_by,pi.resolved_at,pi.verified_by,pi.verified_at,pi.updated_at
              from pilot_incidents pi join pilot_cycles pc on pc.id=pi.cycle_id
             where pc.id=(select id from pilot_cycles order by case when status='CLOSED' then 1 else 0 end,created_at desc limit 1)
             order by case pi.severity when 'CRITICAL' then 1 when 'HIGH' then 2 when 'MEDIUM' then 3 else 4 end,pi.reported_at desc
            """);
        List<Col> cols=List.of(new Col("Incidencia","incident_number"),new Col("Severidad","severity"),new Col("Estado","status"),new Col("Módulo","module"),new Col("Título","title"),new Col("Descripción","description"),new Col("Pasos","steps_to_reproduce"),new Col("Resultado esperado","expected_result"),new Col("Resultado real","actual_result"),new Col("Asignado","assigned_to"),new Col("Resolución","resolution"),new Col("Reportado por","reported_by"),new Col("Reportado","reported_at"),new Col("Resuelto por","resolved_by"),new Col("Resuelto","resolved_at"),new Col("Verificado por","verified_by"),new Col("Verificado","verified_at"));
        return tabular("Incidencias del piloto productivo",rows,cols,"piloto_incidencias",format);
    }

    private static void addMetric(List<Map<String,Object>> rows,String area,String indicator,Object value){Map<String,Object> r=new LinkedHashMap<>();r.put("area",area);r.put("indicator",indicator);r.put("value",value);rows.add(r);}
    private static LocalDate[] reportPeriod(LocalDate from,LocalDate to){if(from==null&&to==null)return new LocalDate[]{LocalDate.of(2000,1,1),LocalDate.now()};LocalDate t=to==null?LocalDate.now():to;LocalDate f=from==null?t.minusDays(29):from;if(f.isAfter(t)){LocalDate x=f;f=t;t=x;}return new LocalDate[]{f,t};}

    private Map<String,Object> oneMap(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}

    public ReportFile documentRegister(String format){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            with refs as (
              select 'SALES_ORDER'::varchar entity_type,so.id entity_id,so.order_number reference,c.name client_name from sales_orders so join clients c on c.id=so.client_id
              union all
              select 'PRODUCTION_ORDER'::varchar,po.id,po.production_number,c.name from production_orders po join clients c on c.id=po.client_id
              union all
              select 'QUALITY_INSPECTION'::varchar,qi.id,qi.inspection_number,c.name from quality_inspections qi join production_orders po on po.id=qi.production_order_id join clients c on c.id=po.client_id
              union all
              select 'DISPATCH'::varchar,d.id,d.dispatch_number,c.name from dispatches d join clients c on c.id=d.client_id
            )
            select d.entity_type,r.reference,r.client_name,d.category,d.title,d.version_no,d.is_current,d.status,d.document_date,
                   d.original_name,d.content_type,d.size_bytes,d.uploaded_by,d.created_at,d.archived_by,d.archived_at
              from business_documents d left join refs r on r.entity_type=d.entity_type and r.entity_id=d.entity_id
             order by d.created_at desc,d.version_no desc
            """);
        List<Col> cols=List.of(new Col("Tipo","entity_type"),new Col("Referencia","reference"),new Col("Cliente","client_name"),new Col("Categoría","category"),new Col("Título","title"),new Col("Versión","version_no"),new Col("Vigente","is_current"),new Col("Estado","status"),new Col("Fecha documento","document_date"),new Col("Archivo","original_name"),new Col("Tipo archivo","content_type"),new Col("Bytes","size_bytes"),new Col("Subido por","uploaded_by"),new Col("Creado","created_at"),new Col("Archivado por","archived_by"),new Col("Archivado","archived_at"));
        return tabular("Registro documental",rows,cols,"registro_documental",format);
    }

    private ReportFile tabular(String title,List<Map<String,Object>> rows,List<Col> cols,String base,String format){
        if("xlsx".equalsIgnoreCase(format))return new ReportFile(xlsx(title,rows,cols),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",base+"_"+LocalDate.now()+".xlsx");
        if("pdf".equalsIgnoreCase(format))return new ReportFile(pdf(title,rows,cols),"application/pdf",base+"_"+LocalDate.now()+".pdf");
        throw new IllegalArgumentException("Formato permitido: xlsx o pdf");
    }

    private byte[] xlsx(String title,List<Map<String,Object>> rows,List<Col> cols){
        try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Map<String,Object> company=company();
            Sheet sh=wb.createSheet("Reporte");
            CellStyle titleStyle=wb.createCellStyle();org.apache.poi.ss.usermodel.Font tf=wb.createFont();tf.setBold(true);tf.setFontHeightInPoints((short)14);titleStyle.setFont(tf);
            CellStyle companyStyle=wb.createCellStyle();org.apache.poi.ss.usermodel.Font cf=wb.createFont();cf.setBold(true);cf.setFontHeightInPoints((short)11);companyStyle.setFont(cf);
            CellStyle header=wb.createCellStyle();org.apache.poi.ss.usermodel.Font hf=wb.createFont();hf.setBold(true);header.setFont(hf);header.setWrapText(true);
            addExcelLogo(wb,sh,company);
            Row r0=sh.createRow(0);Cell companyCell=r0.createCell(1);companyCell.setCellValue(str(company.get("trade_name"),"NG Tintorería"));companyCell.setCellStyle(companyStyle);
            Row r1=sh.createRow(1);r1.createCell(1).setCellValue(companyLine(company));
            Row r2=sh.createRow(2);Cell titleCell=r2.createCell(1);titleCell.setCellValue(title);titleCell.setCellStyle(titleStyle);
            Row r3=sh.createRow(3);r3.createCell(1).setCellValue("Generado: "+LocalDateTime.now().format(DATETIME));
            int r=5;Row hr=sh.createRow(r++);for(int i=0;i<cols.size();i++){Cell c=hr.createCell(i);c.setCellValue(cols.get(i).title());c.setCellStyle(header);}
            for(Map<String,Object> row:rows){Row rr=sh.createRow(r++);for(int i=0;i<cols.size();i++)setCell(rr.createCell(i),row.get(cols.get(i).key()));}
            for(int i=0;i<cols.size();i++){sh.autoSizeColumn(i);sh.setColumnWidth(i,Math.min(Math.max(sh.getColumnWidth(i)+700,2200),16000));}
            Row firstRow=sh.getRow(0);if(firstRow!=null)firstRow.setHeight((short)950);sh.createFreezePane(0,6);wb.write(out);return out.toByteArray();
        }catch(Exception e){throw new IllegalStateException("No se pudo generar Excel",e);}
    }

    private byte[] pdf(String title,List<Map<String,Object>> rows,List<Col> cols){
        Rectangle size=cols.size()>8?PageSize.A4.rotate():PageSize.A4;
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Map<String,Object> company=company();
            Document doc=new Document(size,24,24,28,28);PdfWriter.getInstance(doc,out);doc.open();Font titleFont=new Font(Font.HELVETICA,14,Font.BOLD);Font head=new Font(Font.HELVETICA,7,Font.BOLD);Font body=new Font(Font.HELVETICA,6.5f,Font.NORMAL);Font small=new Font(Font.HELVETICA,7,Font.NORMAL);
            doc.add(documentHeader(company,title,"","Generado: "+LocalDateTime.now().format(DATETIME),titleFont,head,small));doc.add(Chunk.NEWLINE);
            PdfPTable t=new PdfPTable(cols.size());t.setWidthPercentage(100);for(Col c:cols)header(t,c.title(),head);for(Map<String,Object> row:rows)for(Col c:cols)cell(t,display(row.get(c.key())),body);doc.add(t);doc.close();return out.toByteArray();
        }catch(Exception e){throw new IllegalStateException("No se pudo generar PDF",e);}
    }

    private void addExcelLogo(XSSFWorkbook wb,Sheet sh,Map<String,Object> company){
        byte[] logo=logoBytes(company);if(logo==null||logo.length==0)return;
        try{
            String type=str(company.get("logo_content_type"),"");int picType="image/png".equalsIgnoreCase(type)?Workbook.PICTURE_TYPE_PNG:Workbook.PICTURE_TYPE_JPEG;
            int pictureIdx=wb.addPicture(logo,picType);CreationHelper helper=wb.getCreationHelper();Drawing<?> drawing=sh.createDrawingPatriarch();ClientAnchor anchor=helper.createClientAnchor();anchor.setCol1(0);anchor.setRow1(0);Picture picture=drawing.createPicture(anchor,pictureIdx);picture.resize(.72);
        }catch(Exception ignored){}
    }

    private PdfPTable documentHeader(Map<String,Object> company,String documentTitle,String reference,String status,Font title,Font h2,Font small){
        PdfPTable top=new PdfPTable(new float[]{1f,3.2f,2.2f});top.setWidthPercentage(100);
        PdfPCell logoCell=noBorder();byte[] logo=logoBytes(company);
        if(logo!=null){try{Image img=Image.getInstance(logo);img.scaleToFit(58,46);logoCell.addElement(img);}catch(Exception ignored){logoCell.addElement(new Paragraph(initials(str(company.get("trade_name"),"NG")),title));}}
        else logoCell.addElement(new Paragraph(initials(str(company.get("trade_name"),"NG")),title));
        top.addCell(logoCell);
        PdfPCell brand=noBorder();brand.addElement(new Paragraph(str(company.get("trade_name"),"NG Tintorería"),title));
        if(clean(company.get("legal_name"))!=null)brand.addElement(new Paragraph(str(company.get("legal_name"),""),small));
        String id="RUC: "+str(company.get("ruc"),"—");if(clean(company.get("fiscal_address"))!=null)id+=" · "+company.get("fiscal_address");brand.addElement(new Paragraph(id,small));
        String contact=companyContact(company);if(!contact.isBlank())brand.addElement(new Paragraph(contact,small));top.addCell(brand);
        PdfPCell right=noBorder();Paragraph tt=new Paragraph(documentTitle,title);tt.setAlignment(Element.ALIGN_RIGHT);right.addElement(tt);
        if(reference!=null&&!reference.isBlank()){Paragraph no=new Paragraph(reference,h2);no.setAlignment(Element.ALIGN_RIGHT);right.addElement(no);}
        if(status!=null&&!status.isBlank()){Paragraph st=new Paragraph(status,small);st.setAlignment(Element.ALIGN_RIGHT);right.addElement(st);}top.addCell(right);return top;
    }

    private byte[] logoBytes(Map<String,Object> company){Object name=company.get("logo_storage_name");if(name==null||String.valueOf(name).isBlank())return null;try{return companyLogos.readBytes(String.valueOf(name));}catch(Exception ignored){return null;}}
    private static String companyLine(Map<String,Object> company){StringBuilder s=new StringBuilder();if(clean(company.get("ruc"))!=null)s.append("RUC ").append(company.get("ruc"));if(clean(company.get("fiscal_address"))!=null){if(s.length()>0)s.append(" · ");s.append(company.get("fiscal_address"));}String c=companyContact(company);if(!c.isBlank()){if(s.length()>0)s.append(" · ");s.append(c);}return s.toString();}
    private static String companyContact(Map<String,Object> company){StringBuilder s=new StringBuilder();if(clean(company.get("phone"))!=null)s.append(company.get("phone"));if(clean(company.get("email"))!=null){if(s.length()>0)s.append(" · ");s.append(company.get("email"));}return s.toString();}
    private static String initials(String name){if(name==null||name.isBlank())return "NG";String[] parts=name.trim().split("\s+");StringBuilder b=new StringBuilder();for(String p:parts){if(!p.isBlank()&&Character.isLetterOrDigit(p.charAt(0)))b.append(Character.toUpperCase(p.charAt(0)));if(b.length()==2)break;}return b.length()==0?"NG":b.toString();}

    private Map<String,Object> company(){List<Map<String,Object>> r=jdbc.queryForList("select * from company_profile order by updated_at desc limit 1");return r.isEmpty()?Map.of():r.get(0);}
    private static void setCell(Cell c,Object v){if(v==null){c.setBlank();return;}if(v instanceof Number n){c.setCellValue(n.doubleValue());return;}if(v instanceof LocalDate d){c.setCellValue(d.format(DATE));return;}if(v instanceof OffsetDateTime d){c.setCellValue(d.toLocalDateTime().format(DATETIME));return;}c.setCellValue(String.valueOf(v));}
    private static PdfPCell noBorder(){PdfPCell c=new PdfPCell();c.setBorder(Rectangle.NO_BORDER);return c;}
    private static void header(PdfPTable t,String text,Font f){PdfPCell c=new PdfPCell(new Phrase(text,f));c.setPadding(4);t.addCell(c);}
    private static void cell(PdfPTable t,String text,Font f){PdfPCell c=new PdfPCell(new Phrase(text==null?"":text,f));c.setPadding(3);t.addCell(c);}
    private static void cellRight(PdfPTable t,String text,Font f){PdfPCell c=new PdfPCell(new Phrase(text,f));c.setPadding(3);c.setHorizontalAlignment(Element.ALIGN_RIGHT);t.addCell(c);}
    private static void label(PdfPTable t,String text,Font f){PdfPCell c=new PdfPCell(new Phrase(text,f));c.setPadding(4);t.addCell(c);}
    private static void value(PdfPTable t,String text,Font f){PdfPCell c=new PdfPCell(new Phrase(text,f));c.setPadding(4);t.addCell(c);}
    private static void valueRight(PdfPTable t,String text,Font f){PdfPCell c=new PdfPCell(new Phrase(text,f));c.setPadding(4);c.setHorizontalAlignment(Element.ALIGN_RIGHT);t.addCell(c);}
    private static String display(Object v){if(v==null)return "";if(v instanceof OffsetDateTime d)return d.toLocalDateTime().format(DATETIME);if(v instanceof LocalDate d)return d.format(DATE);return String.valueOf(v);}
    private static String fmtDate(Object v){if(v==null)return "—";if(v instanceof LocalDate d)return d.format(DATE);return String.valueOf(v);}
    private static String money(Object v){return String.format(Locale.US,"%,.2f",v==null?0:new BigDecimal(v.toString()));}
    private static String num(Object v){if(v==null)return "0";BigDecimal b=new BigDecimal(v.toString()).stripTrailingZeros();return b.toPlainString();}
    private static String str(Object v,String d){return v==null||String.valueOf(v).isBlank()?d:String.valueOf(v);}
    private static Object clean(Object v){return v==null||String.valueOf(v).trim().isEmpty()?null:v;}
    private static String qualityResultEs(String s){return switch(s){case "APPROVED"->"APROBADO";case "OBSERVED"->"OBSERVADO";case "REPROCESS"->"REPROCESO";case "REJECTED"->"RECHAZADO";case "PENDING"->"PENDIENTE";default->s;};}
    private static String checkResultEs(String s){return switch(s){case "PASS"->"CONFORME";case "OBSERVED"->"OBSERVADO";case "FAIL"->"NO CONFORME";case "PENDING"->"PENDIENTE";default->s;};}
    private static String dispatchStatusEs(String s){return switch(s){case "DRAFT"->"BORRADOR";case "READY"->"LISTO";case "DISPATCHED"->"DESPACHADO";case "DELIVERED"->"ENTREGADO";case "CANCELLED"->"CANCELADO";default->s;};}
    private static String transportModeEs(String s){return switch(s){case "OWN"->"Transporte propio";case "THIRD_PARTY"->"Tercero";case "CLIENT_PICKUP"->"Recojo del cliente";default->s;};}
    private static String returnStatusEs(String s){return switch(s){case "DRAFT"->"BORRADOR";case "RECEIVED"->"RECIBIDA";case "UNDER_REVIEW"->"EN REVISIÓN";case "DECIDED"->"DECIDIDA";case "REPROCESS_CREATED"->"REPROCESO CREADO";case "CLOSED"->"CERRADA";case "CANCELLED"->"CANCELADA";default->s;};}
    private static String returnReasonEs(String s){return switch(s){case "COLOR_VARIATION"->"Diferencia de tono";case "STAINS"->"Manchas";case "DAMAGE"->"Daño";case "MEASUREMENT"->"Peso / medida";case "FINISH"->"Acabado";case "CLIENT_REQUEST"->"Solicitud del cliente";case "GOOD"->"Buen estado";case "OTHER"->"Otro";default->s;};}
    private static String returnDecisionEs(String s){return switch(s){case "PENDING"->"PENDIENTE";case "REPROCESS"->"REPROCESO";case "REPLACEMENT"->"REPOSICIÓN";case "CREDIT"->"CRÉDITO / AJUSTE";case "REJECTED"->"RECLAMO RECHAZADO";default->s;};}
    private static String statusEs(String s){return switch(s){case "DRAFT"->"BORRADOR";case "ISSUED"->"EMITIDA";case "SENT"->"ENVIADA";case "PARTIALLY_RECEIVED"->"RECEPCIÓN PARCIAL";case "RECEIVED"->"RECIBIDA";case "CLOSED"->"CERRADA";case "CANCELLED"->"CANCELADA";default->s;};}
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> castList(Object x){return (List<Map<String,Object>>)x;}
}
