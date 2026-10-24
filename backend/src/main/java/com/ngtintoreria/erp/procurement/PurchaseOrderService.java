package com.ngtintoreria.erp.procurement;

import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.inventory.InventoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class PurchaseOrderService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final InventoryService inventory;

    public PurchaseOrderService(JdbcTemplate jdbc, AuditService audit, InventoryService inventory) {
        this.jdbc=jdbc; this.audit=audit; this.inventory=inventory;
    }

    public record OrderLineInput(UUID id, UUID inventoryItemId, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal lineDiscount) {}
    public record DraftUpdate(UUID supplierId, LocalDate expectedDate, String currency, String paymentTerms,
                              String deliveryAddress, String notes, BigDecimal discount, BigDecimal igvRate,
                              List<OrderLineInput> lines) {}
    public record ManualCreate(UUID supplierId, LocalDate expectedDate, String currency, String paymentTerms,
                               String deliveryAddress, String notes, BigDecimal discount, BigDecimal igvRate,
                               List<OrderLineInput> lines) {}
    public record ActionRequest(String action) {}
    public record ReceiptLine(UUID purchaseOrderItemId, BigDecimal quantity, String lotCode, BigDecimal unitCost) {}
    public record ReceiptRequest(String supplierDocumentType, String supplierDocumentNumber, String notes, List<ReceiptLine> lines) {}

    public List<Map<String,Object>> list(){
        return jdbc.queryForList("""
            select po.id,po.order_number,po.status,po.order_date,po.expected_date,po.currency,po.subtotal,po.discount,po.igv_amount,po.total,
                   po.purchase_request_id,pr.request_number,s.id supplier_id,s.name supplier_name,s.ruc supplier_ruc,
                   count(poi.id) line_count,coalesce(sum(poi.received_quantity),0) received_quantity,
                   coalesce(sum(poi.quantity),0) ordered_quantity,po.created_by,po.created_at,po.updated_at
              from purchase_orders po
              left join purchase_requests pr on pr.id=po.purchase_request_id
              left join suppliers s on s.id=po.supplier_id
              left join purchase_order_items poi on poi.purchase_order_id=po.id
             group by po.id,pr.request_number,s.id,s.name,s.ruc
             order by po.order_date desc,po.created_at desc
            """);
    }

    public Map<String,Object> detail(UUID id){
        List<Map<String,Object>> headers=jdbc.queryForList("""
            select po.*,pr.request_number,pr.production_order_id,prod.production_number,prod.batch_number,
                   s.name supplier_name,s.ruc supplier_ruc,s.contact_name supplier_contact,s.email supplier_email,
                   s.phone supplier_phone,s.payment_terms supplier_default_payment_terms,s.address supplier_address
              from purchase_orders po
              left join purchase_requests pr on pr.id=po.purchase_request_id
              left join production_orders prod on prod.id=pr.production_order_id
              left join suppliers s on s.id=po.supplier_id
             where po.id=?
            """,id);
        if(headers.isEmpty()) throw new IllegalArgumentException("La orden de compra no existe");
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("header",headers.get(0));
        out.put("items",jdbc.queryForList("""
            select poi.*,ii.base_unit,
                   greatest(poi.quantity-poi.received_quantity,0) pending_quantity
              from purchase_order_items poi join inventory_items ii on ii.id=poi.inventory_item_id
             where poi.purchase_order_id=? order by poi.line_no
            """,id));
        out.put("receipts",jdbc.queryForList("""
            select r.id,r.receipt_number,r.received_at,r.supplier_document_type,r.supplier_document_number,r.notes,r.received_by,
                   coalesce(sum(ri.quantity),0) total_quantity
              from purchase_receipts r left join purchase_receipt_items ri on ri.purchase_receipt_id=r.id
             where r.purchase_order_id=? group by r.id order by r.received_at desc
            """,id));
        return out;
    }

    @Transactional
    public Map<String,Object> fromRequest(UUID requestId,String actor){
        Map<String,Object> req=one("select id,request_number,status from purchase_requests where id=? for update",requestId);
        if(req==null) throw new IllegalArgumentException("La solicitud de compra no existe");
        if(!"APPROVED".equals(req.get("status"))) throw new IllegalArgumentException("La solicitud debe estar aprobada antes de crear la orden de compra");
        List<UUID> existing=jdbc.query("""
            select id from purchase_orders where purchase_request_id=? and status<>'CANCELLED' order by created_at desc limit 1
            """,(rs,row)->rs.getObject(1,UUID.class),requestId);
        if(!existing.isEmpty()) return detail(existing.get(0));
        Map<String,Object> company=one("select base_currency,igv_rate,fiscal_address from company_profile order by updated_at desc limit 1");
        String currency=company==null?"PEN":normalCurrency(String.valueOf(company.get("base_currency")));
        BigDecimal igv=company==null?new BigDecimal("18"):bd(company.get("igv_rate"));
        String delivery=company==null?null:clean((String)company.get("fiscal_address"));
        UUID orderId=createHeader(requestId,null,currency,null,delivery,"Originada desde "+req.get("request_number"),BigDecimal.ZERO,igv,actor);
        List<Map<String,Object>> lines=jdbc.queryForList("""
            select pri.id request_item_id,pri.inventory_item_id,pri.material_code,pri.material_name,pri.requested_quantity,pri.unit
              from purchase_request_items pri where pri.purchase_request_id=? order by pri.line_no
            """,requestId);
        if(lines.isEmpty()) throw new IllegalArgumentException("La solicitud no contiene líneas");
        int n=1;
        for(Map<String,Object> line:lines){
            jdbc.update("""
                insert into purchase_order_items(purchase_order_id,purchase_request_item_id,inventory_item_id,item_code,item_name,quantity,unit,unit_price,line_discount,line_total,line_no)
                values (?,?,?,?,?,?,?,0,0,0,?)
                """,orderId,line.get("request_item_id"),line.get("inventory_item_id"),line.get("material_code"),line.get("material_name"),line.get("requested_quantity"),line.get("unit"),n++);
        }
        recalc(orderId);
        audit.record("PURCHASE_ORDER_CREATED","Compras",orderId.toString(),number(orderId)+" · desde "+req.get("request_number")+" · "+actor);
        return detail(orderId);
    }

    @Transactional
    public Map<String,Object> createManual(ManualCreate input,String actor){
        if(input==null||input.lines()==null||input.lines().isEmpty()) throw new IllegalArgumentException("Agregue al menos un insumo a la orden");
        validateSupplier(input.supplierId());
        Map<String,Object> company=one("select base_currency,igv_rate,fiscal_address from company_profile order by updated_at desc limit 1");
        String currency=normalCurrency(input.currency()==null?(company==null?"PEN":String.valueOf(company.get("base_currency"))):input.currency());
        BigDecimal igv=input.igvRate()==null?(company==null?new BigDecimal("18"):bd(company.get("igv_rate"))):nonNegative(input.igvRate(),"IGV inválido");
        String delivery=clean(input.deliveryAddress()); if(delivery==null&&company!=null)delivery=clean((String)company.get("fiscal_address"));
        UUID id=createHeader(null,input.supplierId(),currency,clean(input.paymentTerms()),delivery,clean(input.notes()),nonNegative(input.discount(),"Descuento inválido"),igv,actor);
        int lineNo=1;
        for(OrderLineInput line:input.lines()) insertManualLine(id,line,lineNo++);
        recalc(id);
        audit.record("PURCHASE_ORDER_CREATED","Compras",id.toString(),number(id)+" · reposición/manual · "+actor);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> updateDraft(UUID id,DraftUpdate input,String actor){
        Map<String,Object> current=one("select id,status,purchase_request_id from purchase_orders where id=? for update",id);
        if(current==null)throw new IllegalArgumentException("La orden de compra no existe");
        if(!"DRAFT".equals(current.get("status")))throw new IllegalArgumentException("Solo una orden en borrador puede editarse");
        validateSupplier(input.supplierId());
        BigDecimal discount=nonNegative(input.discount(),"El descuento no puede ser negativo");
        BigDecimal igv=nonNegative(input.igvRate(),"El IGV no puede ser negativo");
        jdbc.update("""
            update purchase_orders set supplier_id=?,expected_date=?,currency=?,payment_terms=?,delivery_address=?,notes=?,discount=?,igv_rate=?,updated_at=now(),version_no=version_no+1 where id=?
            """,input.supplierId(),input.expectedDate(),normalCurrency(input.currency()),clean(input.paymentTerms()),clean(input.deliveryAddress()),clean(input.notes()),discount,igv,id);
        if(input.lines()!=null){
            int next=jdbc.queryForObject("select coalesce(max(line_no),0)+1 from purchase_order_items where purchase_order_id=?",Integer.class,id);
            for(OrderLineInput line:input.lines()){
                if(line.id()==null){
                    if(line.inventoryItemId()!=null)insertManualLine(id,line,next++);
                    continue;
                }
                BigDecimal qty=positive(line.quantity(),"La cantidad debe ser mayor que cero");
                BigDecimal price=nonNegative(line.unitPrice(),"El precio unitario no puede ser negativo");
                BigDecimal lineDiscount=nonNegative(line.lineDiscount(),"El descuento de línea no puede ser negativo");
                BigDecimal received=jdbc.queryForObject("select received_quantity from purchase_order_items where id=? and purchase_order_id=?",BigDecimal.class,line.id(),id);
                if(received==null)throw new IllegalArgumentException("Una línea no pertenece a la orden");
                if(qty.compareTo(received)<0)throw new IllegalArgumentException("La cantidad ordenada no puede ser menor que lo ya recibido");
                String unit=clean(line.unit()); if(unit==null)unit="KG";
                jdbc.update("update purchase_order_items set quantity=?,unit=?,unit_price=?,line_discount=? where id=? and purchase_order_id=?",qty,unit.toUpperCase(Locale.ROOT),price,lineDiscount,line.id(),id);
            }
        }
        recalc(id);
        audit.record("PURCHASE_ORDER_UPDATED","Compras",id.toString(),number(id)+" · "+actor);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> action(UUID id,ActionRequest input,String actor,boolean canApprove){
        Map<String,Object> po=one("select id,status,supplier_id from purchase_orders where id=? for update",id);
        if(po==null)throw new IllegalArgumentException("La orden de compra no existe");
        String current=String.valueOf(po.get("status")), action=normal(input==null?null:input.action());
        switch(action){
            case "ISSUE" -> {
                if(!"DRAFT".equals(current))throw new IllegalArgumentException("Solo una orden en borrador puede emitirse");
                if(!canApprove)throw new IllegalArgumentException("No tiene permiso para emitir órdenes de compra");
                if(po.get("supplier_id")==null)throw new IllegalArgumentException("Seleccione el proveedor antes de emitir la OC");
                Integer lines=jdbc.queryForObject("select count(*) from purchase_order_items where purchase_order_id=?",Integer.class,id);
                Integer priced=jdbc.queryForObject("select count(*) from purchase_order_items where purchase_order_id=? and unit_price>0",Integer.class,id);
                if(lines==null||lines==0||!Objects.equals(lines,priced))throw new IllegalArgumentException("Todas las líneas deben tener cantidad y precio unitario mayor que cero");
                recalc(id);
                jdbc.update("update purchase_orders set status='ISSUED',issued_by=?,issued_at=now(),updated_at=now(),version_no=version_no+1 where id=?",actor,id);
            }
            case "MARK_SENT" -> {
                if(!"ISSUED".equals(current))throw new IllegalArgumentException("La OC debe estar emitida antes de marcarse como enviada");
                jdbc.update("update purchase_orders set status='SENT',sent_at=now(),updated_at=now(),version_no=version_no+1 where id=?",id);
            }
            case "CANCEL" -> {
                if(!Set.of("DRAFT","ISSUED","SENT").contains(current))throw new IllegalArgumentException("La OC ya no puede cancelarse");
                BigDecimal received=jdbc.queryForObject("select coalesce(sum(received_quantity),0) from purchase_order_items where purchase_order_id=?",BigDecimal.class,id);
                if(received!=null&&received.compareTo(BigDecimal.ZERO)>0)throw new IllegalArgumentException("No puede cancelar una OC que ya tiene recepciones");
                jdbc.update("update purchase_orders set status='CANCELLED',updated_at=now(),version_no=version_no+1 where id=?",id);
            }
            case "CLOSE" -> {
                if(!"RECEIVED".equals(current))throw new IllegalArgumentException("Solo una OC recibida completamente puede cerrarse");
                jdbc.update("update purchase_orders set status='CLOSED',closed_at=now(),updated_at=now(),version_no=version_no+1 where id=?",id);
            }
            default -> throw new IllegalArgumentException("Acción de orden de compra no reconocida");
        }
        audit.record("PURCHASE_ORDER_STATUS","Compras",id.toString(),number(id)+" · "+current+" → "+action+" · "+actor);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> receive(UUID id,ReceiptRequest input,String actor){
        Map<String,Object> po=one("select id,order_number,status,supplier_id,purchase_request_id from purchase_orders where id=? for update",id);
        if(po==null)throw new IllegalArgumentException("La orden de compra no existe");
        if(!Set.of("ISSUED","SENT","PARTIALLY_RECEIVED").contains(String.valueOf(po.get("status"))))throw new IllegalArgumentException("La OC no está habilitada para recepción");
        UUID supplierId=(UUID)po.get("supplier_id"); if(supplierId==null)throw new IllegalArgumentException("La OC no tiene proveedor");
        if(input==null||input.lines()==null||input.lines().isEmpty())throw new IllegalArgumentException("Ingrese al menos una cantidad recibida");
        List<ReceiptLine> actual=input.lines().stream().filter(x->x!=null&&x.quantity()!=null&&x.quantity().compareTo(BigDecimal.ZERO)>0).toList();
        if(actual.isEmpty())throw new IllegalArgumentException("Ingrese al menos una cantidad recibida mayor que cero");
        Long seq=jdbc.queryForObject("select nextval('purchase_receipt_number_seq')",Long.class);
        String receiptNumber=String.format("RC-%06d",seq==null?1:seq);
        UUID receiptId=jdbc.queryForObject("""
            insert into purchase_receipts(receipt_number,purchase_order_id,supplier_id,supplier_document_type,supplier_document_number,notes,received_by)
            values (?,?,?,?,?,?,?) returning id
            """,UUID.class,receiptNumber,id,supplierId,clean(input.supplierDocumentType()),clean(input.supplierDocumentNumber()),clean(input.notes()),actor);
        for(ReceiptLine line:actual){
            Map<String,Object> row=one("""
                select poi.id,poi.inventory_item_id,poi.item_code,poi.item_name,poi.quantity,poi.received_quantity,poi.unit_price
                from purchase_order_items poi where poi.id=? and poi.purchase_order_id=? for update
                """,line.purchaseOrderItemId(),id);
            if(row==null)throw new IllegalArgumentException("Una línea recibida no pertenece a la OC");
            BigDecimal qty=positive(line.quantity(),"Cantidad recibida inválida");
            BigDecimal pending=bd(row.get("quantity")).subtract(bd(row.get("received_quantity")));
            if(qty.compareTo(pending)>0)throw new IllegalArgumentException("La recepción de "+row.get("item_name")+" supera el pendiente de "+pending);
            String lot=clean(line.lotCode()); if(lot==null)lot=receiptNumber;
            BigDecimal unitCost=line.unitCost()==null?bd(row.get("unit_price")):nonNegative(line.unitCost(),"Costo unitario inválido");
            jdbc.update("""
                insert into purchase_receipt_items(purchase_receipt_id,purchase_order_item_id,inventory_item_id,quantity,lot_code,unit_cost)
                values (?,?,?,?,?,?)
                """,receiptId,line.purchaseOrderItemId(),row.get("inventory_item_id"),qty,lot,unitCost);
            jdbc.update("update purchase_order_items set received_quantity=received_quantity+? where id=?",qty,line.purchaseOrderItemId());
            inventory.recordMovement(new InventoryService.MovementRequest((UUID)row.get("inventory_item_id"),null,"PURCHASE_RECEIPT",qty,lot,unitCost,"PURCHASE_ORDER",id,String.valueOf(po.get("order_number")),"Recepción "+receiptNumber),actor);
        }
        BigDecimal pendingTotal=jdbc.queryForObject("select coalesce(sum(quantity-received_quantity),0) from purchase_order_items where purchase_order_id=?",BigDecimal.class,id);
        String next=pendingTotal!=null&&pendingTotal.compareTo(BigDecimal.ZERO)==0?"RECEIVED":"PARTIALLY_RECEIVED";
        jdbc.update("update purchase_orders set status=?,updated_at=now(),version_no=version_no+1 where id=?",next,id);
        UUID requestId=(UUID)po.get("purchase_request_id");
        if(requestId!=null){
            List<Map<String,Object>> origins=jdbc.queryForList("""
                select pr.production_order_id,prod.status from purchase_requests pr left join production_orders prod on prod.id=pr.production_order_id where pr.id=?
                """,requestId);
            if(!origins.isEmpty()&&origins.get(0).get("production_order_id")!=null&&"PLANNED".equals(origins.get(0).get("status"))){
                inventory.reserveProductionOrder((UUID)origins.get(0).get("production_order_id"),actor);
            }
        }
        audit.record("PURCHASE_RECEIPT","Compras",receiptId.toString(),receiptNumber+" · "+po.get("order_number")+" · "+actor);
        return detail(id);
    }

    private UUID createHeader(UUID requestId,UUID supplierId,String currency,String paymentTerms,String delivery,String notes,BigDecimal discount,BigDecimal igv,String actor){
        Long seq=jdbc.queryForObject("select nextval('purchase_order_number_seq')",Long.class);
        String orderNumber=String.format("OC-%06d",seq==null?1:seq);
        return jdbc.queryForObject("""
            insert into purchase_orders(order_number,purchase_request_id,supplier_id,currency,payment_terms,delivery_address,notes,discount,igv_rate,created_by)
            values (?,?,?,?,?,?,?,?,?,?) returning id
            """,UUID.class,orderNumber,requestId,supplierId,currency,paymentTerms,delivery,notes,discount,igv,actor);
    }

    private void insertManualLine(UUID orderId,OrderLineInput line,int lineNo){
        if(line==null||line.inventoryItemId()==null)throw new IllegalArgumentException("Seleccione el insumo de cada línea");
        Map<String,Object> item=one("select id,code,name,base_unit from inventory_items where id=? and active=true",line.inventoryItemId());
        if(item==null)throw new IllegalArgumentException("Uno de los insumos no existe o está inactivo");
        BigDecimal qty=positive(line.quantity(),"La cantidad debe ser mayor que cero");
        BigDecimal price=nonNegative(line.unitPrice(),"El precio unitario no puede ser negativo");
        BigDecimal disc=nonNegative(line.lineDiscount(),"El descuento de línea no puede ser negativo");
        String unit=clean(line.unit());if(unit==null)unit=String.valueOf(item.get("base_unit"));
        jdbc.update("""
            insert into purchase_order_items(purchase_order_id,inventory_item_id,item_code,item_name,quantity,unit,unit_price,line_discount,line_total,line_no)
            values (?,?,?,?,?,?,?,?,0,?)
            """,orderId,item.get("id"),item.get("code"),item.get("name"),qty,unit.toUpperCase(Locale.ROOT),price,disc,lineNo);
    }

    private void recalc(UUID orderId){
        List<Map<String,Object>> lines=jdbc.queryForList("select id,quantity,unit_price,line_discount from purchase_order_items where purchase_order_id=?",orderId);
        BigDecimal subtotal=BigDecimal.ZERO;
        for(Map<String,Object> line:lines){
            BigDecimal gross=bd(line.get("quantity")).multiply(bd(line.get("unit_price"))).setScale(4,RoundingMode.HALF_UP);
            BigDecimal disc=bd(line.get("line_discount")).min(gross);
            BigDecimal total=gross.subtract(disc).max(BigDecimal.ZERO).setScale(4,RoundingMode.HALF_UP);
            jdbc.update("update purchase_order_items set line_total=? where id=?",total,line.get("id")); subtotal=subtotal.add(total);
        }
        Map<String,Object> h=one("select discount,igv_rate from purchase_orders where id=?",orderId);
        BigDecimal global=bd(h.get("discount")).min(subtotal);
        BigDecimal taxable=subtotal.subtract(global).max(BigDecimal.ZERO);
        BigDecimal rate=bd(h.get("igv_rate"));
        BigDecimal igv=taxable.multiply(rate).divide(new BigDecimal("100"),4,RoundingMode.HALF_UP);
        BigDecimal total=taxable.add(igv).setScale(4,RoundingMode.HALF_UP);
        jdbc.update("update purchase_orders set subtotal=?,discount=?,igv_amount=?,total=?,updated_at=now() where id=?",subtotal.setScale(4,RoundingMode.HALF_UP),global,igv,total,orderId);
    }

    private void validateSupplier(UUID id){
        if(id==null)return;
        Integer n=jdbc.queryForObject("select count(*) from suppliers where id=? and active=true",Integer.class,id);
        if(n==null||n==0)throw new IllegalArgumentException("El proveedor no existe o está inactivo");
    }
    private String number(UUID id){return jdbc.queryForObject("select order_number from purchase_orders where id=?",String.class,id);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);return r.isEmpty()?null:r.get(0);}
    private static BigDecimal bd(Object x){return x==null?BigDecimal.ZERO:(x instanceof BigDecimal b?b:new BigDecimal(x.toString()));}
    private static BigDecimal positive(BigDecimal x,String msg){if(x==null||x.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException(msg);return x.setScale(6,RoundingMode.HALF_UP);}
    private static BigDecimal nonNegative(BigDecimal x,String msg){if(x==null)return BigDecimal.ZERO;if(x.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException(msg);return x.setScale(6,RoundingMode.HALF_UP);}
    private static String clean(String x){if(x==null)return null;String v=x.trim();return v.isEmpty()?null:v;}
    private static String normal(String x){String v=clean(x);return v==null?"":v.toUpperCase(Locale.ROOT);}
    private static String normalCurrency(String x){String v=normal(x);if(!Set.of("PEN","USD").contains(v))throw new IllegalArgumentException("Moneda permitida: PEN o USD");return v;}
}
