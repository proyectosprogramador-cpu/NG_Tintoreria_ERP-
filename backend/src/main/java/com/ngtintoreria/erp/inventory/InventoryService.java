package com.ngtintoreria.erp.inventory;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class InventoryService {
    private static final Set<String> IN_TYPES = Set.of("INITIAL_STOCK","PURCHASE_RECEIPT","ADJUSTMENT_IN","PRODUCTION_RETURN","TRANSFER_IN");
    private static final Set<String> OUT_TYPES = Set.of("ADJUSTMENT_OUT","PRODUCTION_ISSUE","TRANSFER_OUT");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public InventoryService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ItemRequest(UUID id, String code, String name, String baseUnit, BigDecimal minimumStock, BigDecimal safetyStock, BigDecimal standardCost, Boolean active) {}
    public record MovementRequest(UUID inventoryItemId, UUID warehouseId, String movementType, BigDecimal quantity,
                                  String lotCode, BigDecimal unitCost, String referenceType, UUID referenceId,
                                  String referenceNumber, String comment) {}
    private record RequirementWork(UUID requirementId,UUID itemId,BigDecimal required){}

    public Map<String,Object> summary() {
        Integer total = jdbc.queryForObject("select count(*) from inventory_items where active=true", Integer.class);
        Integer noStock = jdbc.queryForObject("""
            select count(*) from inventory_items i
            where i.active=true and coalesce((select sum(l.current_quantity) from inventory_lots l where l.inventory_item_id=i.id and l.active=true),0)=0
            """, Integer.class);
        Integer low = jdbc.queryForObject("""
            select count(*) from inventory_items i
            where i.active=true
              and coalesce((select sum(l.current_quantity) from inventory_lots l where l.inventory_item_id=i.id and l.active=true),0)>0
              and (coalesce((select sum(l.current_quantity) from inventory_lots l where l.inventory_item_id=i.id and l.active=true),0)
                   -coalesce((select sum(r.quantity) from inventory_reservations r where r.inventory_item_id=i.id and r.status='ACTIVE'),0)) <= i.minimum_stock
            """, Integer.class);
        Integer shortages = jdbc.queryForObject("select count(*) from production_orders where status='PLANNED' and material_status='SHORTAGE'", Integer.class);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("items", nz(total)); out.put("lowStock", nz(low)); out.put("noStock", nz(noStock)); out.put("productionShortages", nz(shortages));
        return out;
    }

    public List<Map<String,Object>> items() {
        return jdbc.query("""
            with physical as (
              select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id
            ), reserved as (
              select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id
            ), purchasing as (
              select poi.inventory_item_id,sum(greatest(poi.quantity-poi.received_quantity,0)) qty
              from purchase_order_items poi join purchase_orders po on po.id=poi.purchase_order_id
              where po.status in ('ISSUED','SENT','PARTIALLY_RECEIVED') group by poi.inventory_item_id
            )
            select i.id,i.code,i.name,i.base_unit,i.minimum_stock,i.safety_stock,i.standard_cost,i.active,
                   coalesce(p.qty,0) physical_quantity,coalesce(r.qty,0) reserved_quantity,
                   greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0) available_quantity,
                   coalesce(pc.qty,0) in_purchase_quantity
            from inventory_items i
            left join physical p on p.inventory_item_id=i.id
            left join reserved r on r.inventory_item_id=i.id
            left join purchasing pc on pc.inventory_item_id=i.id
            order by lower(i.name),i.code
            """, (rs,row)->itemRow(rs));
    }

    public List<Map<String,Object>> movements(int limit) {
        int safe=Math.max(1,Math.min(limit,1000));
        return jdbc.query("""
            select m.id,m.created_at,m.movement_type,m.quantity,m.balance_after,m.reference_type,m.reference_id,
                   m.reference_number,m.comment,m.actor,i.id item_id,i.code item_code,i.name item_name,
                   w.id warehouse_id,w.code warehouse_code,w.name warehouse_name,l.lot_code
            from inventory_movements m
            join inventory_items i on i.id=m.inventory_item_id
            join warehouses w on w.id=m.warehouse_id
            left join inventory_lots l on l.id=m.inventory_lot_id
            order by m.created_at desc,m.id desc limit ?
            """, (rs,row)->movementRow(rs), safe);
    }

    public List<Map<String,Object>> reservations() {
        return jdbc.query("""
            select r.id,r.quantity,r.status,r.created_by,r.created_at,r.released_at,r.release_reason,
                   i.id item_id,i.code item_code,i.name item_name,w.name warehouse_name,
                   po.id production_order_id,po.production_number,po.batch_number,po.priority,
                   pmr.planned_quantity_kg,pmr.shortage_quantity_kg
            from inventory_reservations r
            join inventory_items i on i.id=r.inventory_item_id
            join warehouses w on w.id=r.warehouse_id
            join production_orders po on po.id=r.production_order_id
            join production_material_requirements pmr on pmr.id=r.production_material_requirement_id
            order by case when r.status='ACTIVE' then 0 else 1 end,r.created_at desc
            limit 500
            """, (rs,row)->reservationRow(rs));
    }

    @Transactional
    public Map<String,Object> saveItem(ItemRequest input, String actor) {
        if(input==null)throw new IllegalArgumentException("Ingrese los datos del insumo");
        String code=clean(input.code()); String name=clean(input.name());
        if(code==null||name==null) throw new IllegalArgumentException("Código y nombre del insumo son obligatorios");
        if(code.length()>60||!code.matches("[A-Za-z0-9._/-]+"))
            throw new IllegalArgumentException("El código admite hasta 60 caracteres: letras, números, puntos, guiones y barras");
        if(name.length()>180)throw new IllegalArgumentException("El nombre del insumo admite como máximo 180 caracteres");
        String unit=clean(input.baseUnit()); if(unit==null)unit="KG";
        if(unit.length()>20||!unit.matches("[A-Za-z0-9._/-]+"))
            throw new IllegalArgumentException("La unidad base admite hasta 20 caracteres y no puede contener espacios");
        UUID id=input.id();
        for(Map<String,Object> existing:jdbc.queryForList(
                "select id,code,name from inventory_items where lower(code)=lower(?) or lower(name)=lower(?)",code,name)){
            if(id!=null&&id.equals(existing.get("id")))continue;
            if(code.equalsIgnoreCase(String.valueOf(existing.get("code"))))
                throw new IllegalArgumentException("Ya existe un insumo con el código "+existing.get("code")+". Selecciónelo en el catálogo.");
            throw new IllegalArgumentException("Ya existe un insumo con el nombre "+existing.get("name")+". Selecciónelo en el catálogo.");
        }
        BigDecimal min=nonNegative(input.minimumStock(),"El stock mínimo no puede ser negativo");
        BigDecimal safety=nonNegative(input.safetyStock(),"El stock de seguridad no puede ser negativo");
        BigDecimal standardCost=nullableNonNegative(input.standardCost(),"El costo estándar no puede ser negativo");
        boolean active=input.active()==null||input.active();
        if(id==null){
            id=jdbc.queryForObject("""
                insert into inventory_items(code,name,base_unit,minimum_stock,safety_stock,standard_cost,active)
                values (?,?,?,?,?,?,?) returning id
                """, UUID.class, code.toUpperCase(Locale.ROOT),name,unit.toUpperCase(Locale.ROOT),min,safety,standardCost,active);
        }else{
            int changed=jdbc.update("update inventory_items set code=?,name=?,base_unit=?,minimum_stock=?,safety_stock=?,standard_cost=?,active=?,updated_at=now() where id=?",
                    code.toUpperCase(Locale.ROOT),name,unit.toUpperCase(Locale.ROOT),min,safety,standardCost,active,id);
            if(changed==0)throw new IllegalArgumentException("El insumo no existe");
        }
        audit.record("INVENTORY_ITEM_SAVED","Almacén",id.toString(),code+" · "+name+" · por "+actor);
        return item(id);
    }

    @Transactional
    public Map<String,Object> recordMovement(MovementRequest input, String actor) {
        if(input.inventoryItemId()==null)throw new IllegalArgumentException("Seleccione un insumo");
        String type=normal(input.movementType());
        if(!IN_TYPES.contains(type)&&!OUT_TYPES.contains(type))throw new IllegalArgumentException("Tipo de movimiento no permitido");
        BigDecimal qty=positive(input.quantity(),"La cantidad debe ser mayor que cero");
        UUID warehouse=input.warehouseId()==null?defaultWarehouseId():input.warehouseId();
        lockItem(input.inventoryItemId());
        ensureWarehouse(warehouse);
        if(IN_TYPES.contains(type)) addStock(input.inventoryItemId(),warehouse,type,qty,input,actor);
        else removeStock(input.inventoryItemId(),warehouse,type,qty,input,actor);
        audit.record("INVENTORY_MOVEMENT","Almacén",input.inventoryItemId().toString(),type+" · "+qty+" · "+clean(input.comment()));
        return item(input.inventoryItemId());
    }

    @Transactional
    public Map<String,Object> reserveProductionOrder(UUID orderId, String actor) {
        Map<String,Object> order=one("select id,production_number,status from production_orders where id=? for update",orderId);
        if(order==null)throw new IllegalArgumentException("La orden de producción no existe");
        if(!"PLANNED".equals(order.get("status")))throw new IllegalArgumentException("Solo se reservan materiales de una OP planificada");
        releaseReservationsRaw(orderId,actor,"Reserva recalculada");
        List<Map<String,Object>> reqs=jdbc.queryForList("""
            select id,material_code,material_name,planned_quantity_kg,inventory_item_id
            from production_material_requirements where production_order_id=? order by line_no
            """,orderId);
        if(reqs.isEmpty())throw new IllegalArgumentException("La OP no tiene materiales calculados");
        UUID warehouse=defaultWarehouseId();
        List<RequirementWork> work=new ArrayList<>();
        for(Map<String,Object> req:reqs){
            UUID requirementId=(UUID)req.get("id");
            UUID itemId=(UUID)req.get("inventory_item_id");
            String materialCode=(String)req.get("material_code"), materialName=(String)req.get("material_name");
            if(itemId==null){itemId=ensureItem(materialCode,materialName);jdbc.update("update production_material_requirements set inventory_item_id=? where id=?",itemId,requirementId);}
            work.add(new RequirementWork(requirementId,itemId,bd(req.get("planned_quantity_kg"))));
        }
        work.stream().map(RequirementWork::itemId).distinct().sorted(Comparator.comparing(UUID::toString)).forEach(this::lockItem);
        Map<UUID,BigDecimal> freeByItem=new HashMap<>();
        boolean complete=true;
        for(RequirementWork req:work){
            UUID requirementId=req.requirementId(),currentItem=req.itemId();
            BigDecimal free=freeByItem.computeIfAbsent(currentItem,k->availableForItem(k,warehouse));
            BigDecimal required=req.required();
            BigDecimal reserved=free.min(required).max(BigDecimal.ZERO).setScale(6,RoundingMode.HALF_UP);
            BigDecimal shortage=required.subtract(reserved).max(BigDecimal.ZERO).setScale(6,RoundingMode.HALF_UP);
            if(reserved.compareTo(BigDecimal.ZERO)>0){
                jdbc.update("""
                    insert into inventory_reservations(production_order_id,production_material_requirement_id,warehouse_id,inventory_item_id,quantity,status,created_by)
                    values (?,?,?,?,?,'ACTIVE',?)
                    """,orderId,requirementId,warehouse,currentItem,reserved,actor);
            }
            freeByItem.put(currentItem,free.subtract(reserved).max(BigDecimal.ZERO));
            String state=shortage.compareTo(BigDecimal.ZERO)==0?"RESERVED":"SHORTAGE";
            if(!"RESERVED".equals(state))complete=false;
            jdbc.update("""
                update production_material_requirements
                   set available_quantity_kg=?,shortage_quantity_kg=?,stock_status=?,warehouse_comment=?,checked_by=?,checked_at=now()
                 where id=?
                """,free,shortage,state,
                    "RESERVED".equals(state)?"Stock reservado automáticamente":"Reserva parcial; requiere compra",actor,requirementId);
        }
        String overall=complete?"RESERVED":"SHORTAGE";
        jdbc.update("update production_orders set material_status=?,version_no=version_no+1,updated_at=now() where id=?",overall,orderId);
        jdbc.update("""
            insert into production_events(production_order_id,event_type,from_status,to_status,comment,actor,created_at)
            values (?,'MATERIALS','PLANNED','PLANNED',?,?,now())
            """,orderId,"Reserva automática de almacén: "+overall,actor);
        audit.record("OP_INVENTORY_RESERVED","Almacén",orderId.toString(),order.get("production_number")+" · "+overall);
        return productionMaterialSnapshot(orderId);
    }

    @Transactional
    public void releaseReservationsForOrder(UUID orderId, String actor, String reason) {
        releaseReservationsRaw(orderId,actor,reason==null?"Reserva liberada":reason);
    }

    public Map<String,Object> productionMaterialSnapshot(UUID orderId){
        Map<String,Object> out=new LinkedHashMap<>();
        Map<String,Object> order=one("select id,production_number,material_status,status from production_orders where id=?",orderId);
        if(order==null)throw new IllegalArgumentException("La orden de producción no existe");
        out.put("order",order);
        out.put("materials",jdbc.queryForList("""
            select pmr.id,pmr.line_no,pmr.material_code,pmr.material_name,pmr.planned_quantity_kg,
                   pmr.available_quantity_kg,pmr.shortage_quantity_kg,pmr.stock_status,pmr.warehouse_comment,
                   ii.id inventory_item_id,ii.code inventory_code,ii.name inventory_name,
                   coalesce((select sum(r.quantity) from inventory_reservations r where r.production_material_requirement_id=pmr.id and r.status='ACTIVE'),0) reserved_quantity
            from production_material_requirements pmr
            left join inventory_items ii on ii.id=pmr.inventory_item_id
            where pmr.production_order_id=? order by pmr.line_no
            """,orderId));
        return out;
    }


    @Transactional
    public void issueProductionMaterial(UUID orderId, UUID requirementId, UUID itemId, BigDecimal quantity,
                                        String productionNumber, String actor, String comment) {
        BigDecimal qty=positive(quantity,"La cantidad de consumo debe ser mayor que cero");
        UUID warehouse=defaultWarehouseId();
        lockItem(itemId);
        BigDecimal ownReserved=jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory_reservations where production_material_requirement_id=? and status='ACTIVE'",BigDecimal.class,requirementId);
        BigDecimal physical=physical(itemId,warehouse);
        BigDecimal allReserved=jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory_reservations where inventory_item_id=? and warehouse_id=? and status='ACTIVE'",BigDecimal.class,itemId,warehouse);
        BigDecimal free=physical.subtract(allReserved==null?BigDecimal.ZERO:allReserved).max(BigDecimal.ZERO);
        BigDecimal permitted=(ownReserved==null?BigDecimal.ZERO:ownReserved).add(free);
        if(permitted.compareTo(qty)<0) throw new IllegalArgumentException("Stock no comprometido insuficiente para registrar el consumo real. Disponible para esta OP: "+permitted.setScale(6,RoundingMode.HALF_UP));
        MovementRequest movement=new MovementRequest(itemId,warehouse,"PRODUCTION_ISSUE",qty,null,null,"PRODUCTION_ORDER",orderId,productionNumber,comment);
        removeStock(itemId,warehouse,"PRODUCTION_ISSUE",qty,movement,actor);
        consumeRequirementReservation(requirementId,qty);
        audit.record("PRODUCTION_ISSUE","Almacén",orderId.toString(),productionNumber+" · "+qty+" kg · "+actor);
    }

    @Transactional
    public void returnProductionMaterial(UUID orderId, UUID itemId, BigDecimal quantity, String productionNumber, String actor, String comment) {
        BigDecimal qty=positive(quantity,"La devolución debe ser mayor que cero");
        UUID warehouse=defaultWarehouseId();
        lockItem(itemId);
        MovementRequest movement=new MovementRequest(itemId,warehouse,"PRODUCTION_RETURN",qty,"RET-"+productionNumber,null,"PRODUCTION_ORDER",orderId,productionNumber,comment);
        addStock(itemId,warehouse,"PRODUCTION_RETURN",qty,movement,actor);
        audit.record("PRODUCTION_RETURN","Almacén",orderId.toString(),productionNumber+" · "+qty+" kg · "+actor);
    }

    @Transactional
    public void releaseRequirementReservation(UUID requirementId,String actor,String reason){
        int changed=jdbc.update("""
            update inventory_reservations set status='RELEASED',released_at=now(),release_reason=?
             where production_material_requirement_id=? and status='ACTIVE'
            """,reason,requirementId);
        if(changed>0) audit.record("OP_MATERIAL_RESERVATION_RELEASED","Almacén",requirementId.toString(),reason+" · "+actor);
    }

    private void consumeRequirementReservation(UUID requirementId,BigDecimal quantity){
        BigDecimal remaining=quantity;
        List<Map<String,Object>> reservations=jdbc.queryForList("""
            select id,quantity from inventory_reservations
             where production_material_requirement_id=? and status='ACTIVE'
             order by created_at,id for update
            """,requirementId);
        for(Map<String,Object> row:reservations){
            if(remaining.compareTo(BigDecimal.ZERO)<=0)break;
            UUID id=(UUID)row.get("id");BigDecimal current=bd(row.get("quantity"));
            BigDecimal take=current.min(remaining);
            if(take.compareTo(current)>=0) jdbc.update("update inventory_reservations set status='CONSUMED',released_at=now(),release_reason='Consumido en producción' where id=?",id);
            else jdbc.update("update inventory_reservations set quantity=quantity-? where id=?",take,id);
            remaining=remaining.subtract(take);
        }
    }

    private void releaseReservationsRaw(UUID orderId,String actor,String reason){
        int changed=jdbc.update("""
            update inventory_reservations set status='RELEASED',released_at=now(),release_reason=?
            where production_order_id=? and status='ACTIVE'
            """,reason,orderId);
        if(changed>0) audit.record("OP_INVENTORY_RELEASED","Almacén",orderId.toString(),reason+" · "+changed+" reserva(s) · "+actor);
    }

    private UUID ensureItem(String materialCode,String materialName){
        String name=clean(materialName); if(name==null)name="Insumo sin nombre";
        String code=clean(materialCode);
        if(code==null){code=jdbc.queryForObject("select 'AUTO-' || upper(substr(md5(?),1,8))",String.class,name.toLowerCase(Locale.ROOT));}
        List<UUID> found=jdbc.query("select id from inventory_items where lower(code)=lower(?) or lower(name)=lower(?) order by case when lower(code)=lower(?) then 0 else 1 end limit 1",
                (rs,row)->rs.getObject(1,UUID.class),code,name,code);
        if(!found.isEmpty())return found.get(0);
        return jdbc.queryForObject("insert into inventory_items(code,name) values (?,?) returning id",UUID.class,code.toUpperCase(Locale.ROOT),name);
    }

    private void addStock(UUID itemId,UUID warehouse,String type,BigDecimal qty,MovementRequest input,String actor){
        String lot=clean(input.lotCode()); if(lot==null)lot="GENERAL";
        UUID lotId;
        List<UUID> existing=jdbc.query("select id from inventory_lots where warehouse_id=? and inventory_item_id=? and lot_code=? for update",
                (rs,row)->rs.getObject(1,UUID.class),warehouse,itemId,lot);
        if(existing.isEmpty()) lotId=jdbc.queryForObject("""
            insert into inventory_lots(warehouse_id,inventory_item_id,lot_code,unit_cost,current_quantity)
            values (?,?,?,?,0) returning id
            """,UUID.class,warehouse,itemId,lot,input.unitCost());
        else lotId=existing.get(0);
        jdbc.update("update inventory_lots set current_quantity=current_quantity+?,unit_cost=coalesce(?,unit_cost),updated_at=now() where id=?",qty,input.unitCost(),lotId);
        BigDecimal balance=physical(itemId);
        insertMovement(warehouse,itemId,lotId,type,qty,balance,input,actor);
    }

    private void removeStock(UUID itemId,UUID warehouse,String type,BigDecimal qty,MovementRequest input,String actor){
        BigDecimal total=physical(itemId,warehouse);
        if(total.compareTo(qty)<0)throw new IllegalArgumentException("Stock físico insuficiente. Disponible en almacén: "+total.setScale(6,RoundingMode.HALF_UP));
        BigDecimal remaining=qty;
        List<Map<String,Object>> lots=jdbc.queryForList("""
            select id,current_quantity from inventory_lots
            where warehouse_id=? and inventory_item_id=? and active=true and current_quantity>0
            order by received_at,id for update
            """,warehouse,itemId);
        for(Map<String,Object> lot:lots){
            if(remaining.compareTo(BigDecimal.ZERO)<=0)break;
            UUID lotId=(UUID)lot.get("id"); BigDecimal current=bd(lot.get("current_quantity")); BigDecimal take=current.min(remaining);
            jdbc.update("update inventory_lots set current_quantity=current_quantity-?,updated_at=now() where id=?",take,lotId);
            remaining=remaining.subtract(take);
            BigDecimal balance=physical(itemId);
            insertMovement(warehouse,itemId,lotId,type,take.negate(),balance,input,actor);
        }
    }

    private void insertMovement(UUID warehouse,UUID item,UUID lot,String type,BigDecimal signedQty,BigDecimal balance,MovementRequest input,String actor){
        jdbc.update("""
            insert into inventory_movements(warehouse_id,inventory_item_id,inventory_lot_id,movement_type,quantity,balance_after,
              reference_type,reference_id,reference_number,comment,actor,created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,now())
            """,warehouse,item,lot,type,signedQty,balance,clean(input.referenceType()),input.referenceId(),clean(input.referenceNumber()),clean(input.comment()),actor);
    }

    private Map<String,Object> item(UUID id){
        return items().stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("El insumo no existe"));
    }

    private Map<String,Object> itemRow(ResultSet rs) throws SQLException{
        Map<String,Object> m=new LinkedHashMap<>(); UUID id=rs.getObject("id",UUID.class); BigDecimal physical=rs.getBigDecimal("physical_quantity"),reserved=rs.getBigDecimal("reserved_quantity"),available=rs.getBigDecimal("available_quantity"),minimum=rs.getBigDecimal("minimum_stock");
        m.put("id",id);m.put("code",rs.getString("code"));m.put("name",rs.getString("name"));m.put("baseUnit",rs.getString("base_unit"));m.put("minimumStock",minimum);m.put("safetyStock",rs.getBigDecimal("safety_stock"));m.put("standardCost",rs.getBigDecimal("standard_cost"));m.put("active",rs.getBoolean("active"));
        m.put("physicalQuantity",physical);m.put("reservedQuantity",reserved);m.put("availableQuantity",available);m.put("inPurchaseQuantity",rs.getBigDecimal("in_purchase_quantity"));
        String status=physical.compareTo(BigDecimal.ZERO)==0?"NO_STOCK":available.compareTo(minimum)<=0?"LOW":"NORMAL";m.put("stockStatus",status);return m;
    }
    private Map<String,Object> movementRow(ResultSet rs) throws SQLException{
        Map<String,Object> m=new LinkedHashMap<>();
        for(String c:List.of("id","reference_id","item_id","warehouse_id"))m.put(camel(c),rs.getObject(c));
        m.put("createdAt",rs.getObject("created_at",OffsetDateTime.class));m.put("movementType",rs.getString("movement_type"));m.put("quantity",rs.getBigDecimal("quantity"));m.put("balanceAfter",rs.getBigDecimal("balance_after"));
        m.put("referenceType",rs.getString("reference_type"));m.put("referenceNumber",rs.getString("reference_number"));m.put("comment",rs.getString("comment"));m.put("actor",rs.getString("actor"));m.put("itemCode",rs.getString("item_code"));m.put("itemName",rs.getString("item_name"));m.put("warehouseCode",rs.getString("warehouse_code"));m.put("warehouseName",rs.getString("warehouse_name"));m.put("lotCode",rs.getString("lot_code"));return m;
    }
    private Map<String,Object> reservationRow(ResultSet rs) throws SQLException{
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",rs.getObject("id",UUID.class));m.put("quantity",rs.getBigDecimal("quantity"));m.put("status",rs.getString("status"));m.put("createdBy",rs.getString("created_by"));m.put("createdAt",rs.getObject("created_at",OffsetDateTime.class));m.put("releasedAt",rs.getObject("released_at",OffsetDateTime.class));m.put("releaseReason",rs.getString("release_reason"));
        m.put("itemId",rs.getObject("item_id",UUID.class));m.put("itemCode",rs.getString("item_code"));m.put("itemName",rs.getString("item_name"));m.put("warehouseName",rs.getString("warehouse_name"));m.put("productionOrderId",rs.getObject("production_order_id",UUID.class));m.put("productionNumber",rs.getString("production_number"));m.put("batchNumber",rs.getString("batch_number"));m.put("priority",rs.getString("priority"));m.put("plannedQuantityKg",rs.getBigDecimal("planned_quantity_kg"));m.put("shortageQuantityKg",rs.getBigDecimal("shortage_quantity_kg"));return m;
    }

    private UUID defaultWarehouseId(){return jdbc.queryForObject("select id from warehouses where active=true order by default_warehouse desc,created_at limit 1",UUID.class);}
    private void ensureWarehouse(UUID id){Integer n=jdbc.queryForObject("select count(*) from warehouses where id=? and active=true",Integer.class,id);if(n==null||n==0)throw new IllegalArgumentException("El almacén no existe o está inactivo");}
    private void lockItem(UUID id){List<UUID> x=jdbc.query("select id from inventory_items where id=? and active=true for update",(rs,row)->rs.getObject(1,UUID.class),id);if(x.isEmpty())throw new IllegalArgumentException("El insumo no existe o está inactivo");}
    private BigDecimal physical(UUID item){return jdbc.queryForObject("select coalesce(sum(current_quantity),0) from inventory_lots where inventory_item_id=? and active=true",BigDecimal.class,item);}
    private BigDecimal physical(UUID item,UUID warehouse){return jdbc.queryForObject("select coalesce(sum(current_quantity),0) from inventory_lots where inventory_item_id=? and warehouse_id=? and active=true",BigDecimal.class,item,warehouse);}
    private BigDecimal availableForItem(UUID item,UUID warehouse){BigDecimal p=physical(item,warehouse);BigDecimal r=jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory_reservations where inventory_item_id=? and warehouse_id=? and status='ACTIVE'",BigDecimal.class,item,warehouse);return p.subtract(r).max(BigDecimal.ZERO);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private static BigDecimal bd(Object x){return x==null?BigDecimal.ZERO:(x instanceof BigDecimal b?b:new BigDecimal(x.toString()));}
    private static BigDecimal positive(BigDecimal x,String message){if(x==null||x.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(message);return x.setScale(6,RoundingMode.HALF_UP);}
    private static BigDecimal nonNegative(BigDecimal x,String message){if(x==null)return BigDecimal.ZERO;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(message);return x.setScale(6,RoundingMode.HALF_UP);}
    private static BigDecimal nullableNonNegative(BigDecimal x,String message){if(x==null)return null;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(message);return x.setScale(6,RoundingMode.HALF_UP);}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static String normal(String x){String v=clean(x);return v==null?"":v.toUpperCase(Locale.ROOT);}
    private static int nz(Integer x){return x==null?0:x;}
    private static String camel(String s){StringBuilder b=new StringBuilder();boolean up=false;for(char c:s.toCharArray()){if(c=='_'){up=true;continue;}b.append(up?Character.toUpperCase(c):c);up=false;}return b.toString();}
}
