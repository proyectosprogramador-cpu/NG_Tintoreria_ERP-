package com.ngtintoreria.erp.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngtintoreria.erp.audit.AuditService;
import com.ngtintoreria.erp.crm.Client;
import com.ngtintoreria.erp.crm.ClientRepository;
import com.ngtintoreria.erp.inventory.InventoryService;
import com.ngtintoreria.erp.procurement.Supplier;
import com.ngtintoreria.erp.procurement.SupplierRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class BulkImportService {
    private static final int MAX_ROWS = 5000;
    private static final Set<String> TYPES = Set.of("CLIENTS","SUPPLIERS","INVENTORY_ITEMS","INITIAL_STOCK","PRICE_LISTS");

    private final JdbcTemplate jdbc;
    private final ClientRepository clients;
    private final SupplierRepository suppliers;
    private final InventoryService inventory;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public BulkImportService(JdbcTemplate jdbc, ClientRepository clients, SupplierRepository suppliers,
                             InventoryService inventory, AuditService audit, ObjectMapper mapper) {
        this.jdbc=jdbc; this.clients=clients; this.suppliers=suppliers; this.inventory=inventory; this.audit=audit; this.mapper=mapper;
    }

    public byte[] template(String rawType) {
        String type=type(rawType);
        TemplateSpec spec=spec(type);
        try (XSSFWorkbook wb=new XSSFWorkbook(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sh=wb.createSheet("Plantilla");
            Sheet info=wb.createSheet("Instrucciones");
            CellStyle header=headerStyle(wb);
            CellStyle example=exampleStyle(wb);
            Row h=sh.createRow(0);
            for(int i=0;i<spec.headers.size();i++){
                Cell c=h.createCell(i); c.setCellValue(spec.headers.get(i)); c.setCellStyle(header);
                sh.setColumnWidth(i, Math.min(45,Math.max(14,spec.headers.get(i).length()+4))*256);
            }
            sh.createFreezePane(0,1);
            sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,spec.headers.size()-1));

            Sheet exampleSheet=wb.createSheet("Ejemplo");
            Row eh=exampleSheet.createRow(0);
            for(int i=0;i<spec.headers.size();i++){Cell c=eh.createCell(i);c.setCellValue(spec.headers.get(i));c.setCellStyle(header);exampleSheet.setColumnWidth(i,Math.min(45,Math.max(14,spec.headers.get(i).length()+4))*256);}
            Row sample=exampleSheet.createRow(1);
            for(int i=0;i<spec.sample.size();i++){Cell c=sample.createCell(i); c.setCellValue(spec.sample.get(i)); c.setCellStyle(example);}
            exampleSheet.createFreezePane(0,1);

            info.setColumnWidth(0,28*256); info.setColumnWidth(1,95*256);
            Row title=info.createRow(0); title.createCell(0).setCellValue("Carga inicial - "+spec.title); title.getCell(0).setCellStyle(header);
            title.createCell(1).setCellValue("NG Tintorería ERP"); title.getCell(1).setCellStyle(header);
            String[][] notes={
                    {"Uso","No cambie los nombres de las columnas. La hoja Plantilla viene vacía. Use la hoja Ejemplo solo como referencia; no la copie completa sin revisar."},
                    {"Formato","Use .xlsx. Máximo "+MAX_ROWS+" filas de datos por archivo."},
                    {"Seguridad","La importación siempre se previsualiza. Ningún dato se guarda hasta pulsar Importar registros."},
                    {"Duplicados","Los registros ya existentes se muestran como Omitidos y nunca se sobrescriben automáticamente."},
                    {"Errores","Si hay una fila inválida, corrija el Excel y vuelva a previsualizar. El lote no se confirma con errores."},
                    {"Fechas","Use AAAA-MM-DD, por ejemplo 2026-09-29."},
                    {"Decimales","Use celdas numéricas cuando sea posible. Se aceptan punto o coma decimal."}
            };
            for(int i=0;i<notes.length;i++){Row r=info.createRow(i+2);r.createCell(0).setCellValue(notes[i][0]);r.createCell(1).setCellValue(notes[i][1]);r.getCell(1).getCellStyle().setWrapText(true);}
            info.createFreezePane(0,2);
            wb.write(out); return out.toByteArray();
        } catch(Exception ex){throw new IllegalArgumentException("No se pudo generar la plantilla: "+ex.getMessage());}
    }

    @Transactional
    public Map<String,Object> preview(String rawType, MultipartFile file, String actor) {
        String type=type(rawType);
        if(file==null||file.isEmpty()) throw new IllegalArgumentException("Seleccione un archivo Excel");
        if(file.getSize()>10L*1024*1024) throw new IllegalArgumentException("El archivo supera el límite de 10 MB");
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("carga.xlsx");
        if(!(filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")||filename.toLowerCase(Locale.ROOT).endsWith(".xls")))
            throw new IllegalArgumentException("Solo se permiten archivos Excel .xlsx o .xls");

        UUID batch=jdbc.queryForObject("""
            insert into data_import_batches(import_type,file_name,status,created_by)
            values (?,?, 'PREVIEW', ?) returning id
            """, UUID.class,type,filename,actor);
        int total=0,valid=0,invalid=0,skipped=0;
        Set<String> seen=new HashSet<>();
        Map<String,String> priceHeaders=new HashMap<>();
        try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            Sheet sh=wb.getNumberOfSheets()==0?null:wb.getSheetAt(0);
            if(sh==null||sh.getPhysicalNumberOfRows()==0) throw new IllegalArgumentException("El Excel no contiene filas");
            HeaderMap header=headerMap(sh.getRow(sh.getFirstRowNum()),type);
            for(int rn=sh.getFirstRowNum()+1;rn<=sh.getLastRowNum();rn++){
                Row row=sh.getRow(rn); if(row==null||blank(row,header.columns.values())) continue;
                total++; if(total>MAX_ROWS) throw new IllegalArgumentException("El archivo supera el máximo de "+MAX_ROWS+" filas");
                LinkedHashMap<String,String> raw=readRow(row,header);
                Validation v=validate(type,raw,seen,priceHeaders);
                if("VALID".equals(v.status)) valid++; else if("SKIPPED".equals(v.status)) skipped++; else invalid++;
                jdbc.update("""
                    insert into data_import_rows(batch_id,row_no,status,action,raw_data,normalized_data,error_message)
                    values (?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)
                    """,batch,rn+1,v.status,v.action,json(raw),json(v.data),v.message);
            }
            if(total==0) throw new IllegalArgumentException("El archivo no contiene filas de datos");
            jdbc.update("update data_import_batches set total_rows=?,valid_rows=?,invalid_rows=?,skipped_rows=? where id=?",total,valid,invalid,skipped,batch);
            audit.record("DATA_IMPORT_PREVIEW","Carga inicial",batch.toString(),type+" · "+filename+" · válidas "+valid+" · inválidas "+invalid+" · omitidas "+skipped);
            return batch(batch,250);
        } catch(RuntimeException ex){jdbc.update("update data_import_batches set status='FAILED',notes=? where id=?",cut(ex.getMessage(),500),batch);throw ex;
        } catch(Exception ex){jdbc.update("update data_import_batches set status='FAILED',notes=? where id=?",cut(ex.getMessage(),500),batch);throw new IllegalArgumentException("No se pudo leer el Excel: "+ex.getMessage());}
    }

    public List<Map<String,Object>> batches(int limit){
        int safe=Math.max(1,Math.min(limit,200));
        return jdbc.queryForList("""
            select id,import_type,file_name,status,total_rows,valid_rows,invalid_rows,skipped_rows,imported_rows,
                   created_by,created_at,committed_by,committed_at,notes
              from data_import_batches order by created_at desc limit ?
            """,safe);
    }

    public Map<String,Object> batch(UUID id,int rowLimit){
        Map<String,Object> b=one("""
            select id,import_type,file_name,status,total_rows,valid_rows,invalid_rows,skipped_rows,imported_rows,
                   created_by,created_at,committed_by,committed_at,notes
              from data_import_batches where id=?
            """,id);
        if(b==null) throw new NoSuchElementException();
        int safe=Math.max(1,Math.min(rowLimit,1000));
        b.put("rows",jdbc.queryForList("""
            select id,row_no,status,action,raw_data,normalized_data,error_message,imported_reference
              from data_import_rows where batch_id=? order by row_no limit ?
            """,id,safe));
        return b;
    }

    @Transactional
    public Map<String,Object> commit(UUID batchId,String actor){
        Map<String,Object> b=one("select * from data_import_batches where id=? for update",batchId);
        if(b==null) throw new NoSuchElementException();
        if(!"PREVIEW".equals(String.valueOf(b.get("status")))) throw new IllegalArgumentException("Este lote ya fue procesado o cancelado");
        int invalid=number(b.get("invalid_rows")).intValue();
        int valid=number(b.get("valid_rows")).intValue();
        if(invalid>0) throw new IllegalArgumentException("Corrija las filas inválidas antes de importar");
        if(valid==0){
            jdbc.update("update data_import_batches set status='COMPLETED',imported_rows=0,committed_by=?,committed_at=now(),notes='Sin registros nuevos; todos fueron omitidos' where id=?",actor,batchId);
            return batch(batchId,250);
        }
        String type=String.valueOf(b.get("import_type"));
        List<Map<String,Object>> rows=jdbc.queryForList("select id,row_no,normalized_data from data_import_rows where batch_id=? and status='VALID' order by row_no for update",batchId);
        int imported=switch(type){
            case "CLIENTS" -> importClients(rows);
            case "SUPPLIERS" -> importSuppliers(rows);
            case "INVENTORY_ITEMS" -> importItems(rows,actor);
            case "INITIAL_STOCK" -> importInitialStock(rows,batchId,String.valueOf(b.get("file_name")),actor);
            case "PRICE_LISTS" -> importPriceLists(rows);
            default -> throw new IllegalArgumentException("Tipo de importación no soportado");
        };
        jdbc.update("update data_import_batches set status='COMPLETED',imported_rows=?,committed_by=?,committed_at=now() where id=?",imported,actor,batchId);
        audit.record("DATA_IMPORT_COMMITTED","Carga inicial",batchId.toString(),type+" · importados "+imported+" · por "+actor);
        return batch(batchId,250);
    }

    @Transactional
    public void cancel(UUID batchId,String actor){
        int n=jdbc.update("update data_import_batches set status='CANCELLED',notes=coalesce(notes,'')||? where id=? and status='PREVIEW'"," · Cancelado por "+actor,batchId);
        if(n==0) throw new IllegalArgumentException("Solo puede cancelar una previsualización pendiente");
        audit.record("DATA_IMPORT_CANCELLED","Carga inicial",batchId.toString(),"Cancelado por "+actor);
    }

    private int importClients(List<Map<String,Object>> rows){int n=0;for(Map<String,Object> r:rows){Map<String,Object>d=map(r.get("normalized_data"));Client c=new Client();c.setDocumentNumber(s(d,"documentNumber"));c.setName(s(d,"name"));c.setContactName(s(d,"contactName"));c.setWhatsapp(s(d,"whatsapp"));c.setEmail(s(d,"email"));c.setAddress(s(d,"address"));c.setCreditDays(i(d,"creditDays",0));c.setActive(bool(d,"active",true));Client saved=clients.save(c);markImported(r,saved.getId().toString());n++;}return n;}
    private int importSuppliers(List<Map<String,Object>> rows){int n=0;for(Map<String,Object> r:rows){Map<String,Object>d=map(r.get("normalized_data"));Supplier x=new Supplier();x.setRuc(s(d,"ruc"));x.setName(s(d,"name"));x.setContactName(s(d,"contactName"));x.setEmail(s(d,"email"));x.setPhone(s(d,"phone"));x.setPaymentTerms(s(d,"paymentTerms"));x.setBankReference(s(d,"bankReference"));x.setAddress(s(d,"address"));x.setActive(bool(d,"active",true));Supplier saved=suppliers.save(x);markImported(r,saved.getId().toString());n++;}return n;}
    private int importItems(List<Map<String,Object>> rows,String actor){int n=0;for(Map<String,Object> r:rows){Map<String,Object>d=map(r.get("normalized_data"));Map<String,Object> saved=inventory.saveItem(new InventoryService.ItemRequest(null,s(d,"code"),s(d,"name"),s(d,"baseUnit"),bd(d,"minimumStock",BigDecimal.ZERO),bd(d,"safetyStock",BigDecimal.ZERO),bdNullable(d,"standardCost"),boolObj(d,"active",true)),actor);markImported(r,String.valueOf(saved.get("id")));n++;}return n;}
    private int importInitialStock(List<Map<String,Object>> rows,UUID batchId,String fileName,String actor){int n=0;for(Map<String,Object> r:rows){Map<String,Object>d=map(r.get("normalized_data"));UUID itemId=jdbc.queryForObject("select id from inventory_items where upper(code)=upper(?)",UUID.class,s(d,"code"));BigDecimal q=bd(d,"quantity",BigDecimal.ZERO);String lot=s(d,"lotCode");if(lot==null||lot.isBlank())lot="INICIAL";inventory.recordMovement(new InventoryService.MovementRequest(itemId,null,"INITIAL_STOCK",q,lot,bdNullable(d,"unitCost"),"DATA_IMPORT",batchId,"IMP-"+batchId.toString().substring(0,8).toUpperCase(Locale.ROOT),"Carga inicial desde "+fileName),actor);markImported(r,itemId.toString()+" · "+q);n++;}return n;}

    private int importPriceLists(List<Map<String,Object>> rows){
        Map<String,UUID> listIds=new LinkedHashMap<>(); int n=0;
        for(Map<String,Object> r:rows){
            Map<String,Object>d=map(r.get("normalized_data"));String code=s(d,"listCode");
            UUID listId=listIds.get(code.toUpperCase(Locale.ROOT));
            if(listId==null){
                UUID clientId=null;String doc=s(d,"clientDocument"); if(doc!=null&&!doc.isBlank()) clientId=jdbc.queryForObject("select id from clients where document_number=?",UUID.class,doc);
                listId=jdbc.queryForObject("""
                    insert into price_lists(code,name,client_id,currency,valid_from,valid_to,priority,active,created_at,updated_at)
                    values (?,?,?,?,?,?,?,true,now(),now()) returning id
                    """,UUID.class,code,s(d,"listName"),clientId,s(d,"currency"),LocalDate.parse(s(d,"validFrom")),dateNullable(s(d,"validTo")),i(d,"priority",100));
                listIds.put(code.toUpperCase(Locale.ROOT),listId);
            }
            UUID fabric=idByCode("fabrics",s(d,"fabricCode"));UUID color=idByCode("dye_colors",s(d,"colorCode"));UUID process=idByCode("process_types",s(d,"processCode"));UUID finish=idByCode("finish_types",s(d,"finishCode"));
            UUID ruleId=jdbc.queryForObject("""
                insert into price_rules(price_list_id,fabric_id,color_id,process_id,finish_id,unit,min_qty,max_qty,unit_price,surcharge_pct,discount_pct,active)
                values (?,?,?,?,?,?,?,?,?,?,?,true) returning id
                """,UUID.class,listId,fabric,color,process,finish,s(d,"unit"),bdNullable(d,"minQty"),bdNullable(d,"maxQty"),bd(d,"unitPrice",BigDecimal.ZERO),bd(d,"surchargePct",BigDecimal.ZERO),bd(d,"discountPct",BigDecimal.ZERO));
            markImported(r,code+" · "+ruleId);n++;
        }
        return n;
    }

    private Validation validate(String type,Map<String,String> raw,Set<String> seen,Map<String,String> priceHeaders){
        try{return switch(type){
            case "CLIENTS" -> validateClient(raw,seen);
            case "SUPPLIERS" -> validateSupplier(raw,seen);
            case "INVENTORY_ITEMS" -> validateItem(raw,seen);
            case "INITIAL_STOCK" -> validateStock(raw,seen);
            case "PRICE_LISTS" -> validatePrice(raw,priceHeaders);
            default -> throw new IllegalArgumentException("Tipo no soportado");
        };}catch(IllegalArgumentException ex){return invalid(ex.getMessage(),raw);}
    }

    private Validation validateClient(Map<String,String> r,Set<String> seen){
        String doc=req(r,"Documento"),name=req(r,"Razon social"); if(!seen.add("C:"+doc))return invalid("Documento repetido dentro del archivo",r);
        if(clients.findByDocumentNumber(doc).isPresent())return skip("El cliente ya existe; no se modificará",r);
        int days=intVal(r.get("Dias credito"),0);if(days<0)throw new IllegalArgumentException("Días de crédito no puede ser negativo");
        Map<String,Object>d=new LinkedHashMap<>();d.put("documentNumber",doc);d.put("name",name);d.put("contactName",clean(r.get("Contacto")));d.put("whatsapp",clean(r.get("WhatsApp")));d.put("email",clean(r.get("Email")));d.put("address",clean(r.get("Direccion")));d.put("creditDays",days);d.put("active",booleanVal(r.get("Activo"),true));return valid(d);
    }
    private Validation validateSupplier(Map<String,String> r,Set<String> seen){String ruc=req(r,"RUC"),name=req(r,"Razon social");if(!seen.add("S:"+ruc))return invalid("RUC repetido dentro del archivo",r);if(suppliers.findByRuc(ruc).isPresent())return skip("El proveedor ya existe; no se modificará",r);Map<String,Object>d=new LinkedHashMap<>();d.put("ruc",ruc);d.put("name",name);d.put("contactName",clean(r.get("Contacto")));d.put("email",clean(r.get("Email")));d.put("phone",clean(r.get("Telefono")));d.put("paymentTerms",clean(r.get("Condicion pago")));d.put("bankReference",clean(r.get("Referencia bancaria")));d.put("address",clean(r.get("Direccion")));d.put("active",booleanVal(r.get("Activo"),true));return valid(d);}
    private Validation validateItem(Map<String,String> r,Set<String> seen){String code=req(r,"Codigo"),name=req(r,"Nombre");if(!seen.add("I:"+code.toUpperCase(Locale.ROOT)))return invalid("Código de insumo repetido dentro del archivo",r);Integer exists=jdbc.queryForObject("select count(*) from inventory_items where upper(code)=upper(?)",Integer.class,code);if(exists!=null&&exists>0)return skip("El insumo ya existe; no se modificará",r);BigDecimal min=decimalVal(r.get("Stock minimo"),BigDecimal.ZERO),safe=decimalVal(r.get("Stock seguridad"),BigDecimal.ZERO),cost=decimalNullable(r.get("Costo estandar"));if(min.signum()<0||safe.signum()<0||(cost!=null&&cost.signum()<0))throw new IllegalArgumentException("Stock y costos no pueden ser negativos");Map<String,Object>d=new LinkedHashMap<>();d.put("code",code.toUpperCase(Locale.ROOT));d.put("name",name);d.put("baseUnit",Optional.ofNullable(clean(r.get("Unidad base"))).orElse("KG").toUpperCase(Locale.ROOT));d.put("minimumStock",min);d.put("safetyStock",safe);d.put("standardCost",cost);d.put("active",booleanVal(r.get("Activo"),true));return valid(d);}
    private Validation validateStock(Map<String,String> r,Set<String> seen){String code=req(r,"Codigo insumo").toUpperCase(Locale.ROOT);String lot=Optional.ofNullable(clean(r.get("Lote"))).orElse("INICIAL");String key=code+"|"+lot.toUpperCase(Locale.ROOT);if(!seen.add("ST:"+key))return invalid("El mismo insumo y lote aparece más de una vez en el archivo",r);Integer exists=jdbc.queryForObject("select count(*) from inventory_items where upper(code)=upper(?)",Integer.class,code);if(exists==null||exists==0)throw new IllegalArgumentException("No existe el insumo "+code+". Importe primero el maestro de insumos");BigDecimal qty=decimalVal(r.get("Cantidad"),null);if(qty==null||qty.signum()<=0)throw new IllegalArgumentException("Cantidad debe ser mayor que cero");BigDecimal cost=decimalNullable(r.get("Costo unitario"));if(cost!=null&&cost.signum()<0)throw new IllegalArgumentException("Costo unitario no puede ser negativo");Map<String,Object>d=new LinkedHashMap<>();d.put("code",code);d.put("quantity",qty);d.put("lotCode",lot);d.put("unitCost",cost);return valid(d);}
    private Validation validatePrice(Map<String,String> r,Map<String,String> priceHeaders){
        String code=req(r,"Codigo lista").toUpperCase(Locale.ROOT),name=req(r,"Nombre lista"),currency=Optional.ofNullable(clean(r.get("Moneda"))).orElse("PEN").toUpperCase(Locale.ROOT);if(!Set.of("PEN","USD").contains(currency))throw new IllegalArgumentException("Moneda debe ser PEN o USD");
        Integer exists=jdbc.queryForObject("select count(*) from price_lists where upper(code)=upper(?)",Integer.class,code);if(exists!=null&&exists>0)return skip("La lista de precios ya existe; no se modificará",r);
        LocalDate from=date(req(r,"Vigente desde"));LocalDate to=dateNullable(clean(r.get("Vigente hasta")));if(to!=null&&to.isBefore(from))throw new IllegalArgumentException("Vigente hasta no puede ser anterior a Vigente desde");int priority=intVal(r.get("Prioridad"),100);
        String clientDoc=clean(r.get("Cliente documento"));if(clientDoc!=null){Integer c=jdbc.queryForObject("select count(*) from clients where document_number=?",Integer.class,clientDoc);if(c==null||c==0)throw new IllegalArgumentException("Cliente documento no existe: "+clientDoc);}
        UUID f=idByCodeRequired("fabrics",clean(r.get("Tela codigo")),"Tela");UUID c=idByCodeRequired("dye_colors",clean(r.get("Color codigo")),"Color");UUID p=idByCodeRequired("process_types",clean(r.get("Proceso codigo")),"Proceso");UUID a=idByCodeRequired("finish_types",clean(r.get("Acabado codigo")),"Acabado");
        BigDecimal price=decimalVal(r.get("Precio unitario"),null);if(price==null||price.signum()<0)throw new IllegalArgumentException("Precio unitario es obligatorio y no puede ser negativo");BigDecimal min=decimalNullable(r.get("Cant min")),max=decimalNullable(r.get("Cant max"));if(min!=null&&min.signum()<0||max!=null&&max.signum()<0)throw new IllegalArgumentException("Cantidades mínima/máxima no pueden ser negativas");if(min!=null&&max!=null&&max.compareTo(min)<0)throw new IllegalArgumentException("Cantidad máxima no puede ser menor a la mínima");BigDecimal surcharge=decimalVal(r.get("Recargo pct"),BigDecimal.ZERO),discount=decimalVal(r.get("Descuento pct"),BigDecimal.ZERO);if(surcharge.signum()<0||discount.signum()<0||discount.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Recargo/descuento inválido");
        String signature=name+"|"+currency+"|"+from+"|"+(to==null?"":to)+"|"+priority+"|"+(clientDoc==null?"":clientDoc);String prev=priceHeaders.putIfAbsent(code,signature);if(prev!=null&&!prev.equals(signature))throw new IllegalArgumentException("Las filas de una misma lista deben repetir los mismos datos de cabecera");
        Map<String,Object>d=new LinkedHashMap<>();d.put("listCode",code);d.put("listName",name);d.put("clientDocument",clientDoc);d.put("currency",currency);d.put("validFrom",from.toString());d.put("validTo",to==null?null:to.toString());d.put("priority",priority);d.put("fabricCode",clean(r.get("Tela codigo")));d.put("colorCode",clean(r.get("Color codigo")));d.put("processCode",clean(r.get("Proceso codigo")));d.put("finishCode",clean(r.get("Acabado codigo")));d.put("unit",Optional.ofNullable(clean(r.get("Unidad"))).orElse("KG").toUpperCase(Locale.ROOT));d.put("minQty",min);d.put("maxQty",max);d.put("unitPrice",price);d.put("surchargePct",surcharge);d.put("discountPct",discount);return valid(d);
    }

    private HeaderMap headerMap(Row header,String type){if(header==null)throw new IllegalArgumentException("No se encontró la fila de encabezados");TemplateSpec s=spec(type);Map<String,Integer> available=new HashMap<>();for(Cell c:header){String n=norm(cell(c));if(!n.isBlank())available.put(n,c.getColumnIndex());}Map<String,Integer> cols=new LinkedHashMap<>();for(String h:s.headers){Integer ix=available.get(norm(h));if(ix==null)throw new IllegalArgumentException("Falta la columna obligatoria de plantilla: "+h);cols.put(h,ix);}return new HeaderMap(cols);}
    private LinkedHashMap<String,String> readRow(Row r,HeaderMap h){LinkedHashMap<String,String> out=new LinkedHashMap<>();h.columns.forEach((name,idx)->out.put(name,cell(r.getCell(idx,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))));return out;}
    private boolean blank(Row r,Collection<Integer> indexes){for(int i:indexes){String v=cell(r.getCell(i,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));if(v!=null&&!v.isBlank())return false;}return true;}
    private String cell(Cell c){if(c==null)return "";if(c.getCellType()==CellType.FORMULA){try{switch(c.getCachedFormulaResultType()){case NUMERIC:return numericCell(c);case STRING:return c.getStringCellValue().trim();case BOOLEAN:return String.valueOf(c.getBooleanCellValue());default:return new DataFormatter().formatCellValue(c).trim();}}catch(Exception ignored){return new DataFormatter().formatCellValue(c).trim();}}if(c.getCellType()==CellType.NUMERIC)return numericCell(c);if(c.getCellType()==CellType.BOOLEAN)return c.getBooleanCellValue()?"SI":"NO";return new DataFormatter().formatCellValue(c).trim();}
    private String numericCell(Cell c){if(DateUtil.isCellDateFormatted(c)){return c.getLocalDateTimeCellValue().toLocalDate().toString();}double v=c.getNumericCellValue();return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();}

    private TemplateSpec spec(String type){return switch(type){
        case "CLIENTS" -> new TemplateSpec("Clientes",List.of("Documento","Razon social","Contacto","WhatsApp","Email","Direccion","Dias credito","Activo"),List.of("20123456789","Textiles Ejemplo SAC","Ana Pérez","999888777","compras@ejemplo.pe","Av. Industrial 123","30","SI"));
        case "SUPPLIERS" -> new TemplateSpec("Proveedores",List.of("RUC","Razon social","Contacto","Email","Telefono","Condicion pago","Referencia bancaria","Direccion","Activo"),List.of("20987654321","Química Ejemplo SAC","Luis Ramos","ventas@quimica.pe","987654321","30 días","BCP Cta. 000-000000","Av. Proveedores 450","SI"));
        case "INVENTORY_ITEMS" -> new TemplateSpec("Insumos",List.of("Codigo","Nombre","Unidad base","Stock minimo","Stock seguridad","Costo estandar","Activo"),List.of("INS-001","Sal industrial","KG","100","30","1.25","SI"));
        case "INITIAL_STOCK" -> new TemplateSpec("Stock inicial",List.of("Codigo insumo","Cantidad","Lote","Costo unitario"),List.of("INS-001","500","INICIAL-2026","1.20"));
        case "PRICE_LISTS" -> new TemplateSpec("Listas de precios",List.of("Codigo lista","Nombre lista","Cliente documento","Moneda","Vigente desde","Vigente hasta","Prioridad","Tela codigo","Color codigo","Proceso codigo","Acabado codigo","Unidad","Cant min","Cant max","Precio unitario","Recargo pct","Descuento pct"),List.of("LP-2026","Tarifa general 2026","","PEN","2026-09-29","","100","TEL-ALG","","TENIDO","","KG","0","500","3.50","0","0"));
        default -> throw new IllegalArgumentException("Tipo de plantilla no soportado");};}

    private CellStyle headerStyle(XSSFWorkbook wb){XSSFCellStyle s=wb.createCellStyle();s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);XSSFFont f=wb.createFont();f.setBold(true);f.setColor(IndexedColors.WHITE.getIndex());s.setFont(f);s.setAlignment(HorizontalAlignment.CENTER);s.setVerticalAlignment(VerticalAlignment.CENTER);s.setWrapText(true);return s;}
    private CellStyle exampleStyle(XSSFWorkbook wb){XSSFCellStyle s=wb.createCellStyle();s.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);return s;}

    private UUID idByCode(String table,String code){if(code==null||code.isBlank())return null;return jdbc.queryForObject("select id from "+safeTable(table)+" where upper(code)=upper(?)",UUID.class,code);}
    private UUID idByCodeRequired(String table,String code,String label){if(code==null||code.isBlank())return null;Integer n=jdbc.queryForObject("select count(*) from "+safeTable(table)+" where upper(code)=upper(?)",Integer.class,code);if(n==null||n==0)throw new IllegalArgumentException(label+" no existe: "+code);return jdbc.queryForObject("select id from "+safeTable(table)+" where upper(code)=upper(?)",UUID.class,code);}
    private String safeTable(String t){if(!Set.of("fabrics","dye_colors","process_types","finish_types").contains(t))throw new IllegalArgumentException("Tabla no permitida");return t;}
    private void markImported(Map<String,Object> row,String reference){jdbc.update("update data_import_rows set status='IMPORTED',imported_reference=? where id=?",cut(reference,160),row.get("id"));}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> xs=jdbc.queryForList(sql,args);return xs.isEmpty()?null:new LinkedHashMap<>(xs.get(0));}

    private Validation valid(Map<String,Object>d){return new Validation("VALID","CREATE",d,null);}private Validation invalid(String m,Map<String,String> raw){return new Validation("INVALID","CREATE",new LinkedHashMap<>(raw),m);}private Validation skip(String m,Map<String,String> raw){return new Validation("SKIPPED","SKIP",new LinkedHashMap<>(raw),m);}
    private String type(String s){String t=Optional.ofNullable(s).orElse("").trim().toUpperCase(Locale.ROOT);if(!TYPES.contains(t))throw new IllegalArgumentException("Tipo de importación no válido");return t;}
    private String req(Map<String,String> m,String key){String v=clean(m.get(key));if(v==null)throw new IllegalArgumentException(key+" es obligatorio");return v;}
    private String clean(String s){if(s==null)return null;String v=s.trim();return v.isEmpty()?null:v;}
    private String norm(String s){if(s==null)return "";String x=Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","");return x.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+"," ").trim();}
    private int intVal(String s,int def){String v=clean(s);if(v==null)return def;try{return new BigDecimal(v.replace(",",".")).intValueExact();}catch(Exception e){throw new IllegalArgumentException("Valor entero inválido: "+s);}}
    private boolean booleanVal(String s,boolean def){String v=clean(s);if(v==null)return def;String n=norm(v);if(Set.of("SI","S","TRUE","VERDADERO","1","ACTIVO").contains(n))return true;if(Set.of("NO","N","FALSE","FALSO","0","INACTIVO").contains(n))return false;throw new IllegalArgumentException("Activo debe ser SI o NO");}
    private BigDecimal decimalVal(String s,BigDecimal def){String v=clean(s);if(v==null)return def;try{String z=v.replace(" ","");if(z.contains(",")&&z.contains("."))z=z.replace(",","");else z=z.replace(",",".");return new BigDecimal(z).setScale(6,RoundingMode.HALF_UP).stripTrailingZeros();}catch(Exception e){throw new IllegalArgumentException("Número inválido: "+s);}}
    private BigDecimal decimalNullable(String s){return decimalVal(s,null);}
    private LocalDate date(String s){String v=clean(s);if(v==null)throw new IllegalArgumentException("Fecha obligatoria");for(DateTimeFormatter f:List.of(DateTimeFormatter.ISO_LOCAL_DATE,DateTimeFormatter.ofPattern("d/M/uuuu"),DateTimeFormatter.ofPattern("d-M-uuuu"))){try{return LocalDate.parse(v,f);}catch(DateTimeParseException ignored){}}throw new IllegalArgumentException("Fecha inválida: "+s+". Use AAAA-MM-DD");}
    private LocalDate dateNullable(String s){return clean(s)==null?null:date(s);}
    private Number number(Object o){return o instanceof Number n?n:0;}
    private String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("No se pudo serializar una fila");}}
    private Map<String,Object> map(Object o){try{String s=String.valueOf(o);return mapper.readValue(s,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalArgumentException("Fila de importación dañada");}}
    private String s(Map<String,Object> m,String k){Object v=m.get(k);return v==null?null:String.valueOf(v);}
    private int i(Map<String,Object> m,String k,int def){Object v=m.get(k);if(v==null)return def;return new BigDecimal(String.valueOf(v)).intValue();}
    private boolean bool(Map<String,Object>m,String k,boolean def){Object v=m.get(k);if(v==null)return def;return v instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(v));}
    private Boolean boolObj(Map<String,Object>m,String k,boolean def){return bool(m,k,def);}
    private BigDecimal bd(Map<String,Object>m,String k,BigDecimal def){Object v=m.get(k);return v==null?def:new BigDecimal(String.valueOf(v));}
    private BigDecimal bdNullable(Map<String,Object>m,String k){Object v=m.get(k);return v==null?null:new BigDecimal(String.valueOf(v));}
    private String cut(String s,int n){if(s==null)return null;return s.length()<=n?s:s.substring(0,n);}

    private record TemplateSpec(String title,List<String> headers,List<String> sample){}
    private record HeaderMap(Map<String,Integer> columns){}
    private record Validation(String status,String action,Map<String,Object> data,String message){}
}
