package com.ngtintoreria.erp.dispatch;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {
    private static final Set<String> TRANSPORT_MODES=Set.of("OWN","THIRD_PARTY","CLIENT_PICKUP");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DispatchController(JdbcTemplate jdbc, AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    public record RollRequest(UUID id,String rollCode,BigDecimal weightKg,BigDecimal meters,Integer pieces,String notes){}
    public record DispatchRequest(Integer versionNo, LocalDate dispatchDate,String transportMode,String destinationAddress,
                                  String carrierName,String driverName,String driverDocument,String vehiclePlate,
                                  String guideNumber,String customerReference,String notes,List<RollRequest> rolls){}
    public record ActionRequest(String comment){}
    public record DeliveryRequest(String recipientName,String recipientDocument,String recipientRole,String deliveryNotes){}

    @GetMapping("/eligible")
    @PreAuthorize("hasAuthority('DISPATCH_READ')")
    public List<Map<String,Object>> eligible(){
        return jdbc.queryForList("""
            with sent as (
              select production_order_id,coalesce(sum(total_weight_kg),0) kg
                from dispatches where status in ('DISPATCHED','DELIVERED') group by production_order_id
            )
            select po.id production_order_id,po.production_number,po.batch_number,po.status production_status,
                   po.actual_output_weight_kg,c.name client_name,c.address client_address,so.id sales_order_id,so.order_number,
                   f.name fabric_name,dc.name color_name,pt.name process_name,
                   coalesce(s.kg,0) dispatched_weight_kg,
                   greatest(coalesce(po.actual_output_weight_kg,0)-coalesce(s.kg,0),0) remaining_weight_kg,
                   exists(select 1 from dispatches d where d.production_order_id=po.id and d.status in ('DRAFT','READY')) has_open_dispatch
              from production_orders po join clients c on c.id=po.client_id join sales_orders so on so.id=po.sales_order_id
              join formulas fo on fo.id=po.formula_id join fabrics f on f.id=fo.fabric_id
              join dye_colors dc on dc.id=fo.color_id join process_types pt on pt.id=fo.process_id
              left join sent s on s.production_order_id=po.id
             where po.status in ('READY_FOR_DISPATCH','PARTIALLY_DISPATCHED')
               and greatest(coalesce(po.actual_output_weight_kg,0)-coalesce(s.kg,0),0) > 0
             order by case po.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,po.actual_end,po.updated_at
            """);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DISPATCH_READ')")
    public List<Map<String,Object>> list(@RequestParam(required=false) String status){
        String s=clean(status);
        String sql="""
            select d.id,d.dispatch_number,d.status,d.dispatch_date,d.transport_mode,d.total_rolls,d.total_weight_kg,d.total_meters,
                   d.guide_number,d.actual_dispatch_at,d.delivered_at,d.recipient_name,d.vehicle_plate,d.driver_name,
                   po.production_number,po.batch_number,po.status production_status,po.actual_output_weight_kg,
                   c.name client_name,so.order_number,f.name fabric_name,dc.name color_name
              from dispatches d join production_orders po on po.id=d.production_order_id
              join clients c on c.id=d.client_id join sales_orders so on so.id=d.sales_order_id
              join formulas fo on fo.id=po.formula_id join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id
            """;
        if(s==null)return jdbc.queryForList(sql+" order by d.created_at desc");
        return jdbc.queryForList(sql+" where d.status=? order by d.created_at desc",s.toUpperCase(Locale.ROOT));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DISPATCH_READ')")
    public Map<String,Object> detail(@PathVariable UUID id){return detail0(id);}

    @PostMapping("/orders/{orderId}")
    @Transactional
    @PreAuthorize("hasAuthority('DISPATCH_WRITE')")
    public Map<String,Object> create(@PathVariable UUID orderId,Authentication auth){
        Map<String,Object> order=one("""
            select po.id,po.production_number,po.status,po.sales_order_id,po.client_id,po.actual_output_weight_kg,c.address client_address
              from production_orders po join clients c on c.id=po.client_id where po.id=? for update
            """,orderId);
        if(order==null)throw new IllegalArgumentException("La orden de producción no existe");
        String status=String.valueOf(order.get("status"));
        if(!Set.of("READY_FOR_DISPATCH","PARTIALLY_DISPATCHED").contains(status))throw new IllegalArgumentException("La OP debe estar liberada por Calidad para preparar un despacho");
        Map<String,Object> open=one("select id from dispatches where production_order_id=? and status in ('DRAFT','READY') order by created_at desc limit 1",orderId);
        if(open!=null)return detail0((UUID)open.get("id"));
        BigDecimal remaining=remainingWeight(orderId,null);
        if(remaining.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("La OP ya no tiene kilos pendientes de despacho");
        String number=jdbc.queryForObject("select 'DSP-' || lpad(nextval('dispatch_number_seq')::text,6,'0')",String.class);
        UUID id=jdbc.queryForObject("""
            insert into dispatches(dispatch_number,production_order_id,sales_order_id,client_id,status,dispatch_date,destination_address,created_by)
            values (?,?,?,?, 'DRAFT',current_date,?,?) returning id
            """,UUID.class,number,orderId,order.get("sales_order_id"),order.get("client_id"),order.get("client_address"),auth.getName());
        event(id,"CREATED",null,"DRAFT","Despacho creado desde "+order.get("production_number"),auth.getName());
        audit.record("DESPACHO_CREADO","Despacho",id.toString(),number+" · "+order.get("production_number"));
        return detail0(id);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('DISPATCH_WRITE')")
    public Map<String,Object> save(@PathVariable UUID id,@RequestBody DispatchRequest req,Authentication auth){
        Map<String,Object> d=one("select * from dispatches where id=? for update",id);
        if(d==null)throw new IllegalArgumentException("El despacho no existe");
        if(!"DRAFT".equals(String.valueOf(d.get("status"))))throw new IllegalArgumentException("Solo un despacho en borrador puede editarse");
        int current=((Number)d.get("version_no")).intValue();
        if(req.versionNo()!=null&&req.versionNo()!=current)throw new IllegalArgumentException("El despacho fue modificado por otro usuario. Actualice la pantalla");
        String mode=enumValue(req.transportMode(),TRANSPORT_MODES,"Modo de transporte no válido","OWN");
        LocalDate date=req.dispatchDate()==null?LocalDate.now():req.dispatchDate();
        String destination=clean(req.destinationAddress());
        if(destination==null)throw new IllegalArgumentException("Indique la dirección de destino");
        List<RollRequest> rolls=req.rolls()==null?List.of():req.rolls();
        BigDecimal totalWeight=BigDecimal.ZERO,totalMeters=BigDecimal.ZERO;int totalPieces=0;
        Set<String> codes=new HashSet<>();
        for(RollRequest r:rolls){
            String code=clean(r.rollCode());if(code==null)throw new IllegalArgumentException("Todos los rollos deben tener código");
            if(!codes.add(code.toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("El código de rollo se repite: "+code);
            BigDecimal w=positive(r.weightKg(),"El peso de cada rollo debe ser mayor a cero");
            BigDecimal m=nonNegative(r.meters());int pieces=r.pieces()==null?1:r.pieces();if(pieces<=0)throw new IllegalArgumentException("Las piezas deben ser mayores a cero");
            totalWeight=totalWeight.add(w);totalMeters=totalMeters.add(m);totalPieces+=pieces;
        }
        BigDecimal remaining=remainingWeight((UUID)d.get("production_order_id"),id);
        if(totalWeight.compareTo(remaining)>0)throw new IllegalArgumentException("El despacho excede los kilos pendientes de la OP. Pendiente: "+remaining.stripTrailingZeros().toPlainString()+" kg");
        jdbc.update("""
            update dispatches set dispatch_date=?,transport_mode=?,destination_address=?,carrier_name=?,driver_name=?,driver_document=?,vehicle_plate=?,
                   guide_number=?,customer_reference=?,notes=?,total_rolls=?,total_weight_kg=?,total_meters=?,version_no=version_no+1,updated_at=now()
             where id=?
            """,date,mode,destination,clean(req.carrierName()),clean(req.driverName()),clean(req.driverDocument()),upper(req.vehiclePlate()),clean(req.guideNumber()),clean(req.customerReference()),clean(req.notes()),totalPieces,totalWeight,totalMeters,id);
        jdbc.update("delete from dispatch_rolls where dispatch_id=?",id);
        int line=1;for(RollRequest r:rolls){jdbc.update("""
            insert into dispatch_rolls(dispatch_id,line_no,roll_code,weight_kg,meters,pieces,notes) values (?,?,?,?,?,?,?)
            """,id,line++,clean(r.rollCode()),r.weightKg(),nonNegative(r.meters()),r.pieces()==null?1:r.pieces(),clean(r.notes()));}
        event(id,"UPDATED","DRAFT","DRAFT","Datos de despacho actualizados",auth.getName());
        audit.record("DESPACHO_ACTUALIZADO","Despacho",id.toString(),String.valueOf(d.get("dispatch_number")));
        return detail0(id);
    }

    @PostMapping("/{id}/ready")
    @Transactional
    @PreAuthorize("hasAuthority('DISPATCH_WRITE')")
    public Map<String,Object> ready(@PathVariable UUID id,@RequestBody(required=false) ActionRequest req,Authentication auth){
        Map<String,Object> d=locked(id);
        if(!"DRAFT".equals(String.valueOf(d.get("status"))))throw new IllegalArgumentException("Solo un borrador puede quedar listo para salida");
        BigDecimal weight=bd(d.get("total_weight_kg"));if(weight.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("Agregue al menos un rollo con peso antes de preparar el despacho");
        Integer rolls=((Number)d.get("total_rolls")).intValue();if(rolls<=0)throw new IllegalArgumentException("Agregue al menos un rollo o pieza");
        BigDecimal remaining=remainingWeight((UUID)d.get("production_order_id"),id);
        if(weight.compareTo(remaining)>0)throw new IllegalArgumentException("Los kilos preparados exceden el saldo pendiente de la OP");
        jdbc.update("update dispatches set status='READY',version_no=version_no+1,updated_at=now() where id=?",id);
        event(id,"READY","DRAFT","READY",req==null?null:req.comment(),auth.getName());
        audit.record("DESPACHO_LISTO","Despacho",id.toString(),String.valueOf(d.get("dispatch_number")));
        return detail0(id);
    }

    @PostMapping("/{id}/confirm")
    @Transactional
    @PreAuthorize("hasAuthority('DISPATCH_CONFIRM')")
    public Map<String,Object> confirm(@PathVariable UUID id,@RequestBody(required=false) ActionRequest req,Authentication auth){
        Map<String,Object> d=locked(id);
        if(!"READY".equals(String.valueOf(d.get("status"))))throw new IllegalArgumentException("El despacho debe estar LISTO antes de confirmar la salida física");
        BigDecimal weight=bd(d.get("total_weight_kg"));
        BigDecimal remaining=remainingWeight((UUID)d.get("production_order_id"),id);
        if(weight.compareTo(remaining)>0)throw new IllegalArgumentException("El despacho excede el saldo pendiente de la OP");
        jdbc.update("update dispatches set status='DISPATCHED',actual_dispatch_at=now(),version_no=version_no+1,updated_at=now() where id=?",id);
        event(id,"DISPATCHED","READY","DISPATCHED",req==null?null:req.comment(),auth.getName());
        refreshProductionAndSales((UUID)d.get("production_order_id"),(UUID)d.get("sales_order_id"),auth.getName());
        audit.record("DESPACHO_SALIDA","Despacho",id.toString(),String.valueOf(d.get("dispatch_number"))+" · "+weight+" kg");
        return detail0(id);
    }

    @PostMapping("/{id}/deliver")
    @Transactional
    @PreAuthorize("hasAuthority('DISPATCH_DELIVERY')")
    public Map<String,Object> deliver(@PathVariable UUID id,@RequestBody DeliveryRequest req,Authentication auth){
        Map<String,Object> d=locked(id);
        if(!"DISPATCHED".equals(String.valueOf(d.get("status"))))throw new IllegalArgumentException("Solo un despacho confirmado como salida puede marcarse entregado");
        String recipient=clean(req.recipientName());if(recipient==null)throw new IllegalArgumentException("Indique quién recibió el despacho");
        jdbc.update("""
            update dispatches set status='DELIVERED',recipient_name=?,recipient_document=?,recipient_role=?,delivery_notes=?,delivered_at=now(),
                   version_no=version_no+1,updated_at=now() where id=?
            """,recipient,clean(req.recipientDocument()),clean(req.recipientRole()),clean(req.deliveryNotes()),id);
        event(id,"DELIVERED","DISPATCHED","DELIVERED",req.deliveryNotes(),auth.getName());
        refreshProductionAndSales((UUID)d.get("production_order_id"),(UUID)d.get("sales_order_id"),auth.getName());
        audit.record("DESPACHO_ENTREGADO","Despacho",id.toString(),String.valueOf(d.get("dispatch_number"))+" · recibe "+recipient);
        return detail0(id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    @PreAuthorize("hasAuthority('DISPATCH_WRITE')")
    public Map<String,Object> cancel(@PathVariable UUID id,@RequestBody(required=false) ActionRequest req,Authentication auth){
        Map<String,Object> d=locked(id);String status=String.valueOf(d.get("status"));
        if(!Set.of("DRAFT","READY").contains(status))throw new IllegalArgumentException("Un despacho con salida física ya confirmada no puede cancelarse; debe gestionarse mediante devolución");
        jdbc.update("update dispatches set status='CANCELLED',version_no=version_no+1,updated_at=now() where id=?",id);
        event(id,"CANCELLED",status,"CANCELLED",req==null?null:req.comment(),auth.getName());
        audit.record("DESPACHO_CANCELADO","Despacho",id.toString(),String.valueOf(d.get("dispatch_number")));
        return detail0(id);
    }

    private Map<String,Object> detail0(UUID id){
        Map<String,Object> h=one("""
            with sent as (select production_order_id,coalesce(sum(total_weight_kg),0) kg from dispatches where status in ('DISPATCHED','DELIVERED') group by production_order_id)
            select d.*,po.production_number,po.batch_number,po.status production_status,po.actual_output_weight_kg,
                   c.name client_name,c.document_number client_document,c.address client_address,c.whatsapp client_whatsapp,c.email client_email,
                   so.order_number,f.name fabric_name,dc.name color_name,pt.name process_name,m.code machine_code,m.name machine_name,
                   coalesce(s.kg,0) dispatched_weight_kg,
                   greatest(coalesce(po.actual_output_weight_kg,0)-coalesce(s.kg,0),0) remaining_weight_kg
              from dispatches d join production_orders po on po.id=d.production_order_id join clients c on c.id=d.client_id
              join sales_orders so on so.id=d.sales_order_id join formulas fo on fo.id=po.formula_id
              join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id join process_types pt on pt.id=fo.process_id
              join machines m on m.id=po.machine_id left join sent s on s.production_order_id=po.id where d.id=?
            """,id);
        if(h==null)throw new IllegalArgumentException("El despacho no existe");
        List<Map<String,Object>> rolls=jdbc.queryForList("select * from dispatch_rolls where dispatch_id=? order by line_no",id);
        List<Map<String,Object>> events=jdbc.queryForList("select * from dispatch_events where dispatch_id=? order by created_at",id);
        Map<String,Object> out=new LinkedHashMap<>();out.put("header",h);out.put("rolls",rolls);out.put("events",events);return out;
    }

    private void refreshProductionAndSales(UUID productionOrderId,UUID salesOrderId,String actor){
        Map<String,Object> po=one("select status,actual_output_weight_kg,production_number from production_orders where id=? for update",productionOrderId);
        BigDecimal output=bd(po.get("actual_output_weight_kg"));
        BigDecimal sent=Optional.ofNullable(jdbc.queryForObject("select coalesce(sum(total_weight_kg),0) from dispatches where production_order_id=? and status in ('DISPATCHED','DELIVERED')",BigDecimal.class,productionOrderId)).orElse(BigDecimal.ZERO);
        boolean allSent=output.compareTo(BigDecimal.ZERO)>0&&sent.compareTo(output)>=0;
        boolean allDelivered=allSent&&Boolean.TRUE.equals(jdbc.queryForObject("select not exists(select 1 from dispatches where production_order_id=? and status='DISPATCHED') and exists(select 1 from dispatches where production_order_id=? and status='DELIVERED')",Boolean.class,productionOrderId,productionOrderId));
        String old=String.valueOf(po.get("status"));String next=allDelivered?"DELIVERED":allSent?"DISPATCHED":"PARTIALLY_DISPATCHED";
        if(!old.equals(next)){
            jdbc.update("update production_orders set status=?,updated_at=now() where id=?",next,productionOrderId);
            jdbc.update("insert into production_events(production_order_id,event_type,from_status,to_status,comment,actor,created_at) values (?,'DISPATCH_STATUS',?,?,?, ?,now())",productionOrderId,old,next,"Actualización automática desde Despacho",actor);
        }
        refreshSalesOrder(salesOrderId,actor);
    }

    private void refreshSalesOrder(UUID salesOrderId,String actor){
        Map<String,Object> so=one("select status,order_number from sales_orders where id=? for update",salesOrderId);if(so==null)return;
        boolean anySent=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from dispatches where sales_order_id=? and status in ('DISPATCHED','DELIVERED'))",Boolean.class,salesOrderId));
        boolean allItemsHaveOrders=Boolean.TRUE.equals(jdbc.queryForObject("""
            select not exists(
              select 1 from sales_order_items soi where soi.sales_order_id=?
               and not exists(select 1 from production_orders po where po.sales_order_item_id=soi.id and po.status<>'CANCELLED')
            )
            """,Boolean.class,salesOrderId));
        boolean allDispatched=allItemsHaveOrders&&Boolean.TRUE.equals(jdbc.queryForObject("""
            select not exists(
              select 1 from production_orders po where po.sales_order_id=? and po.status<>'CANCELLED'
               and po.status not in ('DISPATCHED','DELIVERED')
            ) and exists(select 1 from production_orders po where po.sales_order_id=? and po.status<>'CANCELLED')
            """,Boolean.class,salesOrderId,salesOrderId));
        boolean allDelivered=allItemsHaveOrders&&Boolean.TRUE.equals(jdbc.queryForObject("""
            select not exists(
              select 1 from production_orders po where po.sales_order_id=? and po.status<>'CANCELLED' and po.status<>'DELIVERED'
            ) and exists(select 1 from production_orders po where po.sales_order_id=? and po.status<>'CANCELLED')
            """,Boolean.class,salesOrderId,salesOrderId));
        String next=allDelivered?"DELIVERED":allDispatched?"DISPATCHED":anySent?"PARTIALLY_DISPATCHED":null;
        if(next!=null&&!next.equals(String.valueOf(so.get("status")))){
            String old=String.valueOf(so.get("status"));
            jdbc.update("update sales_orders set status=?,updated_at=now() where id=?",next,salesOrderId);
            jdbc.update("insert into sales_order_events(sales_order_id,from_status,to_status,comment,actor,created_at) values (?,?,?,?,?,now())",salesOrderId,old,next,"Actualización automática desde Despacho",actor);
        }
    }

    private BigDecimal remainingWeight(UUID orderId,UUID excludingDispatch){
        BigDecimal output=Optional.ofNullable(jdbc.queryForObject("select actual_output_weight_kg from production_orders where id=?",BigDecimal.class,orderId)).orElse(BigDecimal.ZERO);
        BigDecimal sent;
        if(excludingDispatch==null)sent=Optional.ofNullable(jdbc.queryForObject("select coalesce(sum(total_weight_kg),0) from dispatches where production_order_id=? and status in ('DISPATCHED','DELIVERED')",BigDecimal.class,orderId)).orElse(BigDecimal.ZERO);
        else sent=Optional.ofNullable(jdbc.queryForObject("select coalesce(sum(total_weight_kg),0) from dispatches where production_order_id=? and id<>? and status in ('DISPATCHED','DELIVERED')",BigDecimal.class,orderId,excludingDispatch)).orElse(BigDecimal.ZERO);
        BigDecimal r=output.subtract(sent);return r.compareTo(BigDecimal.ZERO)<0?BigDecimal.ZERO:r.setScale(3,RoundingMode.HALF_UP);
    }

    private Map<String,Object> locked(UUID id){Map<String,Object> d=one("select * from dispatches where id=? for update",id);if(d==null)throw new IllegalArgumentException("El despacho no existe");return d;}
    private void event(UUID id,String type,String from,String to,String comment,String actor){jdbc.update("insert into dispatch_events(dispatch_id,event_type,from_status,to_status,comment,actor) values (?,?,?,?,?,?)",id,type,from,to,clean(comment),actor);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static BigDecimal positive(BigDecimal x,String msg){if(x==null||x.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(msg);return x.setScale(3,RoundingMode.HALF_UP);}
    private static BigDecimal nonNegative(BigDecimal x){if(x==null)return BigDecimal.ZERO.setScale(3);if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException("El valor no puede ser negativo");return x.setScale(3,RoundingMode.HALF_UP);}
    private static BigDecimal bd(Object x){if(x==null)return BigDecimal.ZERO;return new BigDecimal(String.valueOf(x));}
    private static String upper(String x){String v=clean(x);return v==null?null:v.toUpperCase(Locale.ROOT);}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static String enumValue(String value,Set<String> valid,String message,String fallback){String v=clean(value);if(v==null)return fallback;v=v.toUpperCase(Locale.ROOT);if(!valid.contains(v))throw new IllegalArgumentException(message);return v;}
}
