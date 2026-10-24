package com.ngtintoreria.erp.returns;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.maintenance.MaintenanceAvailabilityService;
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
@RequestMapping("/api/returns")
public class CustomerReturnController {
    private static final Set<String> REASONS=Set.of("COLOR_VARIATION","STAINS","DAMAGE","MEASUREMENT","FINISH","CLIENT_REQUEST","OTHER");
    private static final Set<String> CONDITIONS=Set.of("GOOD","COLOR_VARIATION","STAINS","DAMAGE","MEASUREMENT","FINISH","OTHER");
    private static final Set<String> DECISIONS=Set.of("REPROCESS","REPLACEMENT","CREDIT","REJECTED");
    private static final Set<String> PRIORITIES=Set.of("NORMAL","HIGH","URGENT");
    private static final Set<String> SHIFTS=Set.of("MORNING","AFTERNOON","NIGHT","CUSTOM");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MaintenanceAvailabilityService maintenance;

    public CustomerReturnController(JdbcTemplate jdbc,AuditService audit,MaintenanceAvailabilityService maintenance){
        this.jdbc=jdbc;this.audit=audit;this.maintenance=maintenance;
    }

    public record LineRequest(UUID dispatchRollId,BigDecimal returnedWeightKg,BigDecimal returnedMeters,Integer pieces,String conditionCode,String notes){}
    public record ReturnRequest(Integer versionNo,LocalDate returnDate,String reasonCode,String claimReference,String description,
                                BigDecimal returnTransportCost,BigDecimal otherCost,List<LineRequest> lines){}
    public record ReceiveRequest(String receivedBy,String comment){}
    public record DecisionRequest(String decision,String inspectionNotes,String decisionNotes,BigDecimal creditAmount){}
    public record ReprocessRequest(UUID machineId,OffsetDateTime scheduledStart,String priority,String shiftCode,String operatorName,
                                   Integer setupMinutes,Integer cleaningMinutes,String notes){}
    public record CommentRequest(String comment){}

    @GetMapping("/eligible-dispatches")
    @PreAuthorize("hasAuthority('RETURNS_READ')")
    public List<Map<String,Object>> eligibleDispatches(){
        return jdbc.queryForList("""
            select d.id dispatch_id,d.dispatch_number,d.delivered_at,d.total_weight_kg,d.total_rolls,
                   c.name client_name,so.order_number,po.production_number,po.batch_number,
                   f.name fabric_name,dc.name color_name,
                   coalesce((select sum(crl.returned_weight_kg) from customer_return_lines crl
                              join customer_returns cr on cr.id=crl.customer_return_id
                             where cr.dispatch_id=d.id and cr.status<>'CANCELLED'),0) returned_weight_kg,
                   greatest(d.total_weight_kg-coalesce((select sum(crl.returned_weight_kg) from customer_return_lines crl
                              join customer_returns cr on cr.id=crl.customer_return_id
                             where cr.dispatch_id=d.id and cr.status<>'CANCELLED'),0),0) returnable_weight_kg,
                   exists(select 1 from customer_returns cr where cr.dispatch_id=d.id and cr.status='DRAFT') has_open_draft
              from dispatches d join production_orders po on po.id=d.production_order_id
              join sales_orders so on so.id=d.sales_order_id join clients c on c.id=d.client_id
              join formulas fo on fo.id=po.formula_id join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id
             where d.status='DELIVERED'
               and greatest(d.total_weight_kg-coalesce((select sum(crl.returned_weight_kg) from customer_return_lines crl
                              join customer_returns cr on cr.id=crl.customer_return_id
                             where cr.dispatch_id=d.id and cr.status<>'CANCELLED'),0),0)>0
             order by d.delivered_at desc,d.created_at desc
            """);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('RETURNS_READ')")
    public List<Map<String,Object>> list(){
        return jdbc.queryForList("""
            select cr.id,cr.return_number,cr.return_date,cr.status,cr.reason_code,cr.decision,cr.total_rolls,cr.total_weight_kg,
                   cr.return_transport_cost,cr.other_cost,cr.credit_amount,cr.received_at,cr.decision_at,cr.closed_at,
                   d.dispatch_number,po.production_number,po.batch_number,so.order_number,c.name client_name,
                   rpo.production_number reprocess_production_number,rpo.status reprocess_status
              from customer_returns cr join dispatches d on d.id=cr.dispatch_id
              join production_orders po on po.id=cr.source_production_order_id join sales_orders so on so.id=cr.sales_order_id
              join clients c on c.id=cr.client_id left join production_orders rpo on rpo.id=cr.reprocess_production_order_id
             order by cr.created_at desc
            """);
    }

    @GetMapping("/machines")
    @PreAuthorize("hasAuthority('RETURNS_REPROCESS')")
    public List<Map<String,Object>> machines(){
        return jdbc.queryForList("""
            select id,code,name,min_capacity_kg,max_capacity_kg,operational_status
              from machines where active=true and operational_status='AVAILABLE' order by lower(name)
            """);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RETURNS_READ')")
    public Map<String,Object> detail(@PathVariable UUID id){return detail0(id);}

    @PostMapping("/dispatches/{dispatchId}")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_WRITE')")
    public Map<String,Object> create(@PathVariable UUID dispatchId,Authentication auth){
        Map<String,Object> dispatch=one("""
            select d.*,po.production_number,po.id source_production_order_id,so.id sales_order_id,c.id client_id
              from dispatches d join production_orders po on po.id=d.production_order_id
              join sales_orders so on so.id=d.sales_order_id join clients c on c.id=d.client_id
             where d.id=? for update
            """,dispatchId);
        if(dispatch==null)throw new IllegalArgumentException("El despacho no existe");
        if(!"DELIVERED".equals(String.valueOf(dispatch.get("status"))))throw new IllegalArgumentException("Solo un despacho entregado puede originar una devolución");
        Map<String,Object> open=one("select id from customer_returns where dispatch_id=? and status='DRAFT' order by created_at desc limit 1",dispatchId);
        if(open!=null)return detail0((UUID)open.get("id"));
        BigDecimal returnable=dispatchReturnable(dispatchId,null);
        if(returnable.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("El despacho ya no tiene kilos disponibles para devolución");
        String number=jdbc.queryForObject("select 'DEV-' || lpad(nextval('customer_return_number_seq')::text,6,'0')",String.class);
        UUID id=jdbc.queryForObject("""
            insert into customer_returns(return_number,dispatch_id,source_production_order_id,sales_order_id,client_id,status,reason_code,return_date,created_by)
            values (?,?,?,?,?,'DRAFT','OTHER',current_date,?) returning id
            """,UUID.class,number,dispatchId,dispatch.get("source_production_order_id"),dispatch.get("sales_order_id"),dispatch.get("client_id"),auth.getName());
        event(id,"CREATED",null,"DRAFT","Devolución creada desde "+dispatch.get("dispatch_number"),auth.getName());
        audit.record("DEVOLUCION_CREADA","Devoluciones",id.toString(),number+" · "+dispatch.get("dispatch_number"));
        return detail0(id);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_WRITE')")
    public Map<String,Object> save(@PathVariable UUID id,@RequestBody ReturnRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);
        if(!"DRAFT".equals(String.valueOf(cr.get("status"))))throw new IllegalArgumentException("Solo una devolución en borrador puede editarse");
        int current=((Number)cr.get("version_no")).intValue();
        if(req.versionNo()!=null&&req.versionNo()!=current)throw new IllegalArgumentException("La devolución fue modificada por otro usuario. Actualice la pantalla");
        String reason=enumValue(req.reasonCode(),REASONS,"Motivo de devolución no válido","OTHER");
        List<LineRequest> lines=req.lines()==null?List.of():req.lines();
        if(lines.size()>100)throw new IllegalArgumentException("La devolución no puede superar 100 líneas");
        Set<UUID> ids=new HashSet<>();BigDecimal totalWeight=BigDecimal.ZERO,totalMeters=BigDecimal.ZERO;int totalPieces=0;
        List<Object[]> inserts=new ArrayList<>();int lineNo=1;
        for(LineRequest line:lines){
            if(line.dispatchRollId()==null)continue;
            if(!ids.add(line.dispatchRollId()))throw new IllegalArgumentException("Un rollo no puede repetirse en la devolución");
            BigDecimal weight=positive(line.returnedWeightKg(),"El peso devuelto debe ser mayor que cero");
            Map<String,Object> roll=one("select id,roll_code,weight_kg,meters,pieces from dispatch_rolls where id=? and dispatch_id=?",line.dispatchRollId(),cr.get("dispatch_id"));
            if(roll==null)throw new IllegalArgumentException("Uno de los rollos no pertenece al despacho");
            BigDecimal original=bd(roll.get("weight_kg"));
            BigDecimal used=Optional.ofNullable(jdbc.queryForObject("""
                select coalesce(sum(crl.returned_weight_kg),0) from customer_return_lines crl
                 join customer_returns x on x.id=crl.customer_return_id
                where crl.dispatch_roll_id=? and x.id<>? and x.status<>'CANCELLED'
                """,BigDecimal.class,line.dispatchRollId(),id)).orElse(BigDecimal.ZERO);
            BigDecimal available=original.subtract(used);
            if(weight.compareTo(available)>0)throw new IllegalArgumentException("El rollo "+roll.get("roll_code")+" solo tiene "+available.stripTrailingZeros().toPlainString()+" kg disponibles para devolución");
            BigDecimal meters=nonNegative(line.returnedMeters());int pieces=line.pieces()==null?1:line.pieces();
            if(pieces<=0)throw new IllegalArgumentException("Las piezas deben ser mayores que cero");
            String condition=enumValue(line.conditionCode(),CONDITIONS,"Condición de rollo no válida","OTHER");
            totalWeight=totalWeight.add(weight);totalMeters=totalMeters.add(meters);totalPieces+=pieces;
            inserts.add(new Object[]{id,lineNo++,line.dispatchRollId(),roll.get("roll_code"),original,weight,meters,pieces,condition,clean(line.notes())});
        }
        jdbc.update("""
            update customer_returns set return_date=?,reason_code=?,claim_reference=?,description=?,return_transport_cost=?,other_cost=?,
                   total_rolls=?,total_weight_kg=?,total_meters=?,version_no=version_no+1,updated_at=now() where id=?
            """,req.returnDate()==null?LocalDate.now():req.returnDate(),reason,clean(req.claimReference()),clean(req.description()),
            nonNegativeMoney(req.returnTransportCost()),nonNegativeMoney(req.otherCost()),totalPieces,totalWeight.setScale(3,RoundingMode.HALF_UP),totalMeters.setScale(3,RoundingMode.HALF_UP),id);
        jdbc.update("delete from customer_return_lines where customer_return_id=?",id);
        for(Object[] x:inserts)jdbc.update("""
            insert into customer_return_lines(customer_return_id,line_no,dispatch_roll_id,roll_code,dispatched_weight_kg,returned_weight_kg,returned_meters,pieces,condition_code,notes)
            values (?,?,?,?,?,?,?,?,?,?)
            """,x);
        event(id,"UPDATED","DRAFT","DRAFT","Datos de devolución actualizados",auth.getName());
        audit.record("DEVOLUCION_ACTUALIZADA","Devoluciones",id.toString(),String.valueOf(cr.get("return_number")));
        return detail0(id);
    }

    @PostMapping("/{id}/receive")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_WRITE')")
    public Map<String,Object> receive(@PathVariable UUID id,@RequestBody(required=false) ReceiveRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);
        if(!"DRAFT".equals(String.valueOf(cr.get("status"))))throw new IllegalArgumentException("Solo un borrador puede recepcionarse");
        if(bd(cr.get("total_weight_kg")).compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("Registre al menos un rollo o pieza devuelta");
        String receiver=req==null?null:clean(req.receivedBy());if(receiver==null)receiver=auth.getName();
        jdbc.update("update customer_returns set status='RECEIVED',received_by=?,received_at=now(),version_no=version_no+1,updated_at=now() where id=?",receiver,id);
        holdSalesOrder((UUID)cr.get("sales_order_id"),auth.getName(),"Devolución "+cr.get("return_number")+" recibida");
        event(id,"RECEIVED","DRAFT","RECEIVED",req==null?null:clean(req.comment()),auth.getName());
        audit.record("DEVOLUCION_RECIBIDA","Devoluciones",id.toString(),cr.get("return_number")+" · "+cr.get("total_weight_kg")+" kg");
        return detail0(id);
    }

    @PostMapping("/{id}/review")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_DECIDE')")
    public Map<String,Object> review(@PathVariable UUID id,@RequestBody(required=false) CommentRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);
        if(!"RECEIVED".equals(String.valueOf(cr.get("status"))))throw new IllegalArgumentException("La devolución debe estar recibida antes de iniciar la revisión");
        jdbc.update("update customer_returns set status='UNDER_REVIEW',version_no=version_no+1,updated_at=now() where id=?",id);
        event(id,"REVIEW_STARTED","RECEIVED","UNDER_REVIEW",req==null?null:clean(req.comment()),auth.getName());
        return detail0(id);
    }

    @PostMapping("/{id}/decision")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_DECIDE')")
    public Map<String,Object> decide(@PathVariable UUID id,@RequestBody DecisionRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);String status=String.valueOf(cr.get("status"));
        if(!Set.of("RECEIVED","UNDER_REVIEW").contains(status))throw new IllegalArgumentException("La devolución debe estar recibida o en revisión para registrar una decisión");
        String decision=enumValue(req.decision(),DECISIONS,"Resolución de devolución no válida",null);
        if(decision==null)throw new IllegalArgumentException("Seleccione una resolución");
        BigDecimal credit=nonNegativeMoney(req.creditAmount());
        jdbc.update("""
            update customer_returns set status='DECIDED',decision=?,inspection_notes=?,decision_notes=?,credit_amount=?,
                   decision_by=?,decision_at=now(),version_no=version_no+1,updated_at=now() where id=?
            """,decision,clean(req.inspectionNotes()),clean(req.decisionNotes()),credit,auth.getName(),id);
        event(id,"DECIDED",status,"DECIDED","Resolución: "+decision+(clean(req.decisionNotes())==null?"":" · "+clean(req.decisionNotes())),auth.getName());
        audit.record("DEVOLUCION_DECIDIDA","Devoluciones",id.toString(),cr.get("return_number")+" · "+decision);
        return detail0(id);
    }

    @PostMapping("/{id}/reprocess")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_REPROCESS')")
    public Map<String,Object> createReprocess(@PathVariable UUID id,@RequestBody ReprocessRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);String returnStatus=String.valueOf(cr.get("status"));
        if(!Set.of("DECIDED","REPROCESS_CREATED").contains(returnStatus)||!"REPROCESS".equals(String.valueOf(cr.get("decision"))))throw new IllegalArgumentException("La devolución debe estar decidida como REPROCESO");
        if(cr.get("reprocess_production_order_id")!=null){
            String linkedStatus=jdbc.queryForObject("select status from production_orders where id=?",String.class,cr.get("reprocess_production_order_id"));
            if(!"CANCELLED".equals(linkedStatus))throw new IllegalArgumentException("La devolución ya tiene una OP de reproceso activa");
        }
        if(req.machineId()==null)throw new IllegalArgumentException("Seleccione una máquina");
        if(req.scheduledStart()==null)throw new IllegalArgumentException("Indique la fecha y hora programada");
        Map<String,Object> machine=one("select * from machines where id=? for update",req.machineId());
        if(machine==null||Boolean.FALSE.equals(machine.get("active")))throw new IllegalArgumentException("La máquina no existe o está inactiva");
        if(!"AVAILABLE".equalsIgnoreCase(String.valueOf(machine.get("operational_status"))))throw new IllegalArgumentException("La máquina no está disponible para programación");
        BigDecimal weight=bd(cr.get("total_weight_kg"));
        validateCapacity(weight,bdNullable(machine.get("min_capacity_kg")),bdNullable(machine.get("max_capacity_kg")));
        Map<String,Object> source=one("""
            select po.*,lrv.bath_ratio,lrv.time_minutes
              from production_orders po join lab_recipe_versions lrv on lrv.id=po.lab_recipe_version_id
             where po.id=? for update
            """,cr.get("source_production_order_id"));
        if(source==null)throw new IllegalArgumentException("No se encontró la OP original");
        List<Map<String,Object>> route=jdbc.queryForList("select operation_code,operation_name,planned_minutes from production_operations where production_order_id=? order by sequence_no",source.get("id"));
        int operationMinutes=route.stream().mapToInt(x->((Number)x.get("planned_minutes")).intValue()).sum();
        if(operationMinutes<=0){Object raw=source.get("time_minutes");operationMinutes=raw==null?60:((Number)raw).intValue();}
        int setup=req.setupMinutes()==null?intValue(source.get("setup_minutes")):nonNegativeInt(req.setupMinutes(),"Setup no válido");
        int cleaning=req.cleaningMinutes()==null?intValue(source.get("cleaning_minutes")):nonNegativeInt(req.cleaningMinutes(),"Limpieza no válida");
        OffsetDateTime end=req.scheduledStart().plusMinutes((long)setup+operationMinutes+cleaning);
        maintenance.assertNoProductionConflict(req.machineId(),req.scheduledStart(),end,null);
        maintenance.assertNoMaintenanceConflict(req.machineId(),req.scheduledStart(),end);
        BigDecimal bathRatio=bd(source.get("bath_ratio"));
        BigDecimal bathVolume=weight.multiply(bathRatio).setScale(3,RoundingMode.HALF_UP);
        Long seq=jdbc.queryForObject("select nextval('production_order_number_seq')",Long.class);
        String productionNumber="OP-"+String.format("%06d",seq==null?0:seq);
        Integer priorReprocess=jdbc.queryForObject("select count(*)::int from production_orders where source_return_id=?",Integer.class,id);
        String batchNumber=String.valueOf(cr.get("return_number"))+"-R"+((priorReprocess==null?0:priorReprocess)+1);
        UUID poId=jdbc.queryForObject("""
            insert into production_orders(production_number,batch_number,lab_request_id,lab_recipe_version_id,formula_id,formula_version_id,
              sales_order_id,sales_order_item_id,client_id,machine_id,status,material_status,priority,shift_code,operator_name,color_family,
              batch_weight_kg,bath_volume_l,scheduled_start,scheduled_end,estimated_minutes,setup_minutes,cleaning_minutes,notes,version_no,
              created_by,created_at,updated_at,source_return_id)
            values (?,?,?,?,?,?,?,?,?,?,'PLANNED','PENDING',?,?,?,?,?,?,?,?,?,?,?,?,1,?,now(),now(),?) returning id
            """,UUID.class,productionNumber,batchNumber,source.get("lab_request_id"),source.get("lab_recipe_version_id"),source.get("formula_id"),source.get("formula_version_id"),
            source.get("sales_order_id"),source.get("sales_order_item_id"),source.get("client_id"),req.machineId(),
            enumValue(req.priority(),PRIORITIES,"Prioridad no válida","HIGH"),enumValue(req.shiftCode(),SHIFTS,"Turno no válido","CUSTOM"),clean(req.operatorName()),source.get("color_family"),
            weight,bathVolume,req.scheduledStart(),end,operationMinutes,setup,cleaning,mergeNotes("Reproceso por devolución "+cr.get("return_number"),req.notes()),auth.getName(),id);
        List<Map<String,Object>> mats=jdbc.queryForList("""
            select line_no,inventory_item_id,material_code,material_name,dosage,dosage_unit
              from production_material_requirements where production_order_id=? order by line_no
            """,source.get("id"));
        int line=1;
        for(Map<String,Object> m:mats){
            BigDecimal dosage=bd(m.get("dosage"));String unit=String.valueOf(m.get("dosage_unit"));
            BigDecimal qty="G_L".equals(unit)?bathVolume.multiply(dosage).divide(new BigDecimal("1000"),6,RoundingMode.HALF_UP):weight.multiply(dosage).divide(new BigDecimal("100"),6,RoundingMode.HALF_UP);
            jdbc.update("""
                insert into production_material_requirements(production_order_id,line_no,inventory_item_id,material_code,material_name,dosage,dosage_unit,planned_quantity_kg,shortage_quantity_kg,stock_status)
                values (?,?,?,?,?,?,?,?,0,'PENDING')
                """,poId,line++,m.get("inventory_item_id"),m.get("material_code"),m.get("material_name"),dosage,unit,qty);
        }
        int opLine=1;
        if(route.isEmpty()){
            jdbc.update("insert into production_operations(production_order_id,sequence_no,operation_code,operation_name,planned_minutes,status) values (?,?,?,?,?,'PENDING')",poId,1,"REPROCESS","Reproceso por devolución",operationMinutes);
        }else{
            for(Map<String,Object> op:route){
                String name="Reproceso · "+String.valueOf(op.get("operation_name"));if(name.length()>180)name=name.substring(0,180);
                jdbc.update("insert into production_operations(production_order_id,sequence_no,operation_code,operation_name,planned_minutes,status) values (?,?,?,?,?,'PENDING')",poId,opLine++,op.get("operation_code"),name,op.get("planned_minutes"));
            }
        }
        jdbc.update("insert into production_events(production_order_id,event_type,from_status,to_status,comment,actor,created_at) values (?,'RETURN_REPROCESS',null,'PLANNED',?,?,now())",poId,"Creada desde devolución "+cr.get("return_number"),auth.getName());
        jdbc.update("update customer_returns set status='REPROCESS_CREATED',reprocess_production_order_id=?,version_no=version_no+1,updated_at=now() where id=?",poId,id);
        event(id,"REPROCESS_CREATED",returnStatus,"REPROCESS_CREATED","OP de reproceso "+productionNumber+" creada",auth.getName());
        audit.record("DEVOLUCION_REPROCESO","Devoluciones",id.toString(),cr.get("return_number")+" → "+productionNumber);
        return detail0(id);
    }

    @PostMapping("/{id}/close")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_DECIDE')")
    public Map<String,Object> close(@PathVariable UUID id,@RequestBody(required=false) CommentRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);String status=String.valueOf(cr.get("status"));
        if(!Set.of("DECIDED","REPROCESS_CREATED").contains(status))throw new IllegalArgumentException("La devolución debe tener una decisión antes de cerrarse");
        if("REPROCESS".equals(String.valueOf(cr.get("decision")))){
            if(cr.get("reprocess_production_order_id")==null)throw new IllegalArgumentException("Primero cree la OP de reproceso");
            String ps=jdbc.queryForObject("select status from production_orders where id=?",String.class,cr.get("reprocess_production_order_id"));
            if(!"DELIVERED".equals(ps))throw new IllegalArgumentException("La OP de reproceso debe estar entregada antes de cerrar la devolución");
        }
        jdbc.update("update customer_returns set status='CLOSED',closed_by=?,closed_at=now(),version_no=version_no+1,updated_at=now() where id=?",auth.getName(),id);
        event(id,"CLOSED",status,"CLOSED",req==null?null:clean(req.comment()),auth.getName());
        refreshSalesOrderAfterClose((UUID)cr.get("sales_order_id"),auth.getName());
        audit.record("DEVOLUCION_CERRADA","Devoluciones",id.toString(),String.valueOf(cr.get("return_number")));
        return detail0(id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    @PreAuthorize("hasAuthority('RETURNS_WRITE')")
    public Map<String,Object> cancel(@PathVariable UUID id,@RequestBody(required=false) CommentRequest req,Authentication auth){
        Map<String,Object> cr=locked(id);
        if(!"DRAFT".equals(String.valueOf(cr.get("status"))))throw new IllegalArgumentException("Solo una devolución en borrador puede cancelarse");
        jdbc.update("update customer_returns set status='CANCELLED',version_no=version_no+1,updated_at=now() where id=?",id);
        event(id,"CANCELLED","DRAFT","CANCELLED",req==null?null:clean(req.comment()),auth.getName());
        audit.record("DEVOLUCION_CANCELADA","Devoluciones",id.toString(),String.valueOf(cr.get("return_number")));
        return detail0(id);
    }

    private Map<String,Object> detail0(UUID id){
        Map<String,Object> h=one("""
            select cr.*,d.dispatch_number,d.delivered_at,d.guide_number,d.total_weight_kg dispatch_weight_kg,
                   po.production_number source_production_number,po.batch_number source_batch_number,
                   so.order_number,c.name client_name,c.document_number client_document,c.address client_address,
                   f.name fabric_name,dc.name color_name,pt.name process_name,
                   rpo.production_number reprocess_production_number,rpo.batch_number reprocess_batch_number,rpo.status reprocess_status
              from customer_returns cr join dispatches d on d.id=cr.dispatch_id
              join production_orders po on po.id=cr.source_production_order_id join sales_orders so on so.id=cr.sales_order_id
              join clients c on c.id=cr.client_id join formulas fo on fo.id=po.formula_id
              join fabrics f on f.id=fo.fabric_id join dye_colors dc on dc.id=fo.color_id join process_types pt on pt.id=fo.process_id
              left join production_orders rpo on rpo.id=cr.reprocess_production_order_id
             where cr.id=?
            """,id);
        if(h==null)throw new IllegalArgumentException("La devolución no existe");
        List<Map<String,Object>> lines=jdbc.queryForList("select * from customer_return_lines where customer_return_id=? order by line_no",id);
        List<Map<String,Object>> available=jdbc.queryForList("""
            select dr.id dispatch_roll_id,dr.roll_code,dr.weight_kg dispatched_weight_kg,dr.meters dispatched_meters,dr.pieces dispatched_pieces,
                   coalesce((select sum(crl.returned_weight_kg) from customer_return_lines crl join customer_returns x on x.id=crl.customer_return_id
                              where crl.dispatch_roll_id=dr.id and x.id<>? and x.status<>'CANCELLED'),0) returned_other_kg,
                   greatest(dr.weight_kg-coalesce((select sum(crl.returned_weight_kg) from customer_return_lines crl join customer_returns x on x.id=crl.customer_return_id
                              where crl.dispatch_roll_id=dr.id and x.id<>? and x.status<>'CANCELLED'),0),0) max_returnable_weight_kg
              from dispatch_rolls dr where dr.dispatch_id=? order by dr.line_no
            """,id,id,h.get("dispatch_id"));
        List<Map<String,Object>> events=jdbc.queryForList("select * from customer_return_events where customer_return_id=? order by created_at",id);
        Map<String,Object> out=new LinkedHashMap<>();out.put("header",h);out.put("lines",lines);out.put("dispatchRolls",available);out.put("events",events);return out;
    }

    private void holdSalesOrder(UUID salesOrderId,String actor,String comment){
        Map<String,Object> so=one("select status,order_number from sales_orders where id=? for update",salesOrderId);if(so==null)return;
        String old=String.valueOf(so.get("status"));if("CANCELLED".equals(old)||"ON_HOLD".equals(old))return;
        jdbc.update("update sales_orders set status='ON_HOLD',updated_at=now() where id=?",salesOrderId);
        jdbc.update("insert into sales_order_events(sales_order_id,from_status,to_status,comment,actor,created_at) values (?,?,?,?,?,now())",salesOrderId,old,"ON_HOLD",comment,actor);
    }

    private void refreshSalesOrderAfterClose(UUID salesOrderId,String actor){
        Integer active=jdbc.queryForObject("select count(*) from customer_returns where sales_order_id=? and status not in ('CLOSED','CANCELLED')",Integer.class,salesOrderId);
        if(active!=null&&active>0)return;
        Boolean allDelivered=jdbc.queryForObject("""
            select not exists(select 1 from production_orders where sales_order_id=? and status<>'CANCELLED' and status<>'DELIVERED')
               and exists(select 1 from production_orders where sales_order_id=? and status<>'CANCELLED')
            """,Boolean.class,salesOrderId,salesOrderId);
        if(!Boolean.TRUE.equals(allDelivered))return;
        Map<String,Object> so=one("select status from sales_orders where id=? for update",salesOrderId);if(so==null)return;
        String old=String.valueOf(so.get("status"));if("DELIVERED".equals(old))return;
        jdbc.update("update sales_orders set status='DELIVERED',updated_at=now() where id=?",salesOrderId);
        jdbc.update("insert into sales_order_events(sales_order_id,from_status,to_status,comment,actor,created_at) values (?,?,?,?,?,now())",salesOrderId,old,"DELIVERED","Devoluciones cerradas y entregas completadas",actor);
    }

    private BigDecimal dispatchReturnable(UUID dispatchId,UUID excludeReturnId){
        BigDecimal total=Optional.ofNullable(jdbc.queryForObject("select total_weight_kg from dispatches where id=?",BigDecimal.class,dispatchId)).orElse(BigDecimal.ZERO);
        String sql="""
            select coalesce(sum(crl.returned_weight_kg),0) from customer_return_lines crl
             join customer_returns cr on cr.id=crl.customer_return_id
            where cr.dispatch_id=? and cr.status<>'CANCELLED'"""+(excludeReturnId==null?"":" and cr.id<>?");
        BigDecimal used=excludeReturnId==null?jdbc.queryForObject(sql,BigDecimal.class,dispatchId):jdbc.queryForObject(sql,BigDecimal.class,dispatchId,excludeReturnId);
        BigDecimal remaining=total.subtract(used==null?BigDecimal.ZERO:used);return remaining.max(BigDecimal.ZERO).setScale(3,RoundingMode.HALF_UP);
    }

    private Map<String,Object> locked(UUID id){Map<String,Object> x=one("select * from customer_returns where id=? for update",id);if(x==null)throw new IllegalArgumentException("La devolución no existe");return x;}
    private void event(UUID id,String type,String from,String to,String comment,String actor){jdbc.update("insert into customer_return_events(customer_return_id,event_type,from_status,to_status,comment,actor) values (?,?,?,?,?,?)",id,type,from,to,clean(comment),actor);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static BigDecimal bd(Object x){if(x==null)return BigDecimal.ZERO;return x instanceof BigDecimal b?b:new BigDecimal(String.valueOf(x));}
    private static BigDecimal bdNullable(Object x){return x==null?null:bd(x);}
    private static BigDecimal positive(BigDecimal x,String msg){if(x==null||x.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(msg);return x.setScale(3,RoundingMode.HALF_UP);}
    private static BigDecimal nonNegative(BigDecimal x){if(x==null)return BigDecimal.ZERO.setScale(3);if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException("El valor no puede ser negativo");return x.setScale(3,RoundingMode.HALF_UP);}
    private static BigDecimal nonNegativeMoney(BigDecimal x){if(x==null)return BigDecimal.ZERO.setScale(4);if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException("El costo no puede ser negativo");return x.setScale(4,RoundingMode.HALF_UP);}
    private static int nonNegativeInt(Integer x,String msg){if(x==null)return 0;if(x<0)throw new IllegalArgumentException(msg);return x;}
    private static int intValue(Object x){return x==null?0:((Number)x).intValue();}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static String mergeNotes(String prefix,String notes){String n=clean(notes);return n==null?prefix:prefix+". "+n;}
    private static String enumValue(String value,Set<String> valid,String message,String fallback){String v=clean(value);if(v==null)return fallback;v=v.toUpperCase(Locale.ROOT);if(!valid.contains(v))throw new IllegalArgumentException(message);return v;}
    private static void validateCapacity(BigDecimal weight,BigDecimal min,BigDecimal max){
        if(weight==null||weight.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("El peso del reproceso debe ser mayor que cero");
        if(min!=null&&min.compareTo(BigDecimal.ZERO)>0&&weight.compareTo(min)<0)throw new IllegalArgumentException("El lote devuelto está por debajo de la capacidad mínima de la máquina ("+min+" kg)");
        if(max!=null&&max.compareTo(BigDecimal.ZERO)>0&&weight.compareTo(max)>0)throw new IllegalArgumentException("El lote devuelto supera la capacidad máxima de la máquina ("+max+" kg)");
    }
}
