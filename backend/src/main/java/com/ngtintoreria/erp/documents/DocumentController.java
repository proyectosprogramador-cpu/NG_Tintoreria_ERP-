package com.ngtintoreria.erp.documents;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private static final Set<String> TYPES=Set.of("SALES_ORDER","PRODUCTION_ORDER","QUALITY_INSPECTION","DISPATCH");
    private static final Set<String> CATEGORIES=Set.of("CUSTOMER_PO","FABRIC_SPEC","TECHNICAL_SHEET","LAB_RESULT","QUALITY_EVIDENCE","DELIVERY_PROOF","COMMERCIAL","OTHER");
    private final JdbcTemplate jdbc;
    private final DocumentStorageService storage;
    private final AuditService audit;

    public DocumentController(JdbcTemplate jdbc,DocumentStorageService storage,AuditService audit){this.jdbc=jdbc;this.storage=storage;this.audit=audit;}

    @GetMapping("/entities")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_READ')")
    public List<Map<String,Object>> entities(@RequestParam String type,@RequestParam(required=false) String q,Authentication auth){
        String t=type(type);requireScope(auth,t,false);String like="%"+(q==null?"":q.trim().toLowerCase(Locale.ROOT))+"%";
        return switch(t){
            case "SALES_ORDER" -> jdbc.queryForList("""
                select so.id,so.order_number reference,c.name client_name,so.status,
                       concat(so.order_number,' · ',c.name,' · ',so.status) label
                from sales_orders so join clients c on c.id=so.client_id
                where lower(so.order_number||' '||c.name) like ? order by so.created_at desc limit 200
                """,like);
            case "PRODUCTION_ORDER" -> jdbc.queryForList("""
                select po.id,po.production_number reference,c.name client_name,po.status,
                       concat(po.production_number,' · ',po.batch_number,' · ',c.name) label
                from production_orders po join clients c on c.id=po.client_id
                where lower(po.production_number||' '||po.batch_number||' '||c.name) like ? order by po.created_at desc limit 200
                """,like);
            case "QUALITY_INSPECTION" -> jdbc.queryForList("""
                select qi.id,qi.inspection_number reference,c.name client_name,qi.result status,
                       concat(qi.inspection_number,' · ',po.production_number,' · ',c.name) label
                from quality_inspections qi join production_orders po on po.id=qi.production_order_id join clients c on c.id=po.client_id
                where lower(qi.inspection_number||' '||po.production_number||' '||c.name) like ? order by qi.created_at desc limit 200
                """,like);
            case "DISPATCH" -> jdbc.queryForList("""
                select d.id,d.dispatch_number reference,c.name client_name,d.status,
                       concat(d.dispatch_number,' · ',po.production_number,' · ',c.name) label
                from dispatches d join production_orders po on po.id=d.production_order_id join clients c on c.id=d.client_id
                where lower(d.dispatch_number||' '||po.production_number||' '||c.name) like ? order by d.created_at desc limit 200
                """,like);
            default -> List.of();
        };
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_READ')")
    public List<Map<String,Object>> list(@RequestParam String entityType,@RequestParam UUID entityId,@RequestParam(defaultValue="false") boolean includeArchived,@RequestParam(defaultValue="false") boolean includeHistory,Authentication auth){
        String t=type(entityType);requireScope(auth,t,false);ensureEntity(t,entityId);
        String archived=includeArchived?"":" and d.status='ACTIVE' ";
        String history=includeHistory?"":" and d.is_current=true ";
        return jdbc.queryForList("""
            select d.id,d.entity_type,d.entity_id,d.category,d.title,d.description,d.document_date,d.document_group_id,d.version_no,
                   d.is_current,d.status,d.original_name,d.content_type,d.size_bytes,d.uploaded_by,d.created_at,d.archived_by,d.archived_at
            from business_documents d where d.entity_type=? and d.entity_id=?
            """+archived+history+" order by d.is_current desc,d.created_at desc,d.version_no desc",t,entityId);
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_READ')")
    public List<Map<String,Object>> recent(@RequestParam(defaultValue="100") int limit,Authentication auth){
        int n=Math.max(1,Math.min(limit,300));
        return jdbc.queryForList("""
            select d.id,d.entity_type,d.entity_id,d.category,d.title,d.description,d.document_date,d.version_no,d.is_current,d.status,
                   d.original_name,d.content_type,d.size_bytes,d.uploaded_by,d.created_at
            from business_documents d where d.status='ACTIVE' order by d.created_at desc limit ?
            """,n).stream().filter(x->hasScope(auth,String.valueOf(x.get("entity_type")),false)).toList();
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_WRITE')")
    public Map<String,Object> upload(@RequestParam String entityType,@RequestParam UUID entityId,@RequestParam String category,
                                     @RequestParam String title,@RequestParam(required=false) String description,
                                     @RequestParam(required=false) LocalDate documentDate,@RequestPart("file") MultipartFile file,
                                     Authentication auth){
        String t=type(entityType);requireScope(auth,t,true);ensureEntity(t,entityId);String cat=category(category);String cleanTitle=text(title,180,true);String desc=text(description,1000,false);
        var stored=storage.save(file);UUID id=UUID.randomUUID(),group=UUID.randomUUID();
        try{
            jdbc.update("""
                insert into business_documents(id,entity_type,entity_id,category,title,description,document_date,document_group_id,version_no,is_current,status,
                                               original_name,storage_name,content_type,size_bytes,uploaded_by)
                values(?,?,?,?,?,?,?,?,1,true,'ACTIVE',?,?,?,?,?)
                """,id,t,entityId,cat,cleanTitle,desc,documentDate,group,stored.originalName(),stored.storageName(),stored.contentType(),stored.sizeBytes(),auth.getName());
        }catch(RuntimeException e){storage.delete(stored.storageName());throw e;}
        audit.record("DOCUMENT_UPLOADED","DOCUMENTS",id.toString(),t+" "+entityId+" · "+cleanTitle+" · "+stored.originalName());
        return required(id);
    }

    @PostMapping(value="/{id}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_WRITE')")
    @Transactional
    public Map<String,Object> newVersion(@PathVariable UUID id,@RequestParam(required=false) String description,@RequestPart("file") MultipartFile file,Authentication auth){
        Map<String,Object> cur=requiredForUpdate(id);String t=String.valueOf(cur.get("entity_type"));requireScope(auth,t,true);
        if(!Boolean.TRUE.equals(cur.get("is_current")))throw new IllegalArgumentException("Seleccione la versión vigente del documento");
        if(!"ACTIVE".equals(String.valueOf(cur.get("status"))))throw new IllegalArgumentException("El documento está archivado");
        var stored=storage.save(file);UUID newId=UUID.randomUUID();int version=((Number)cur.get("version_no")).intValue()+1;UUID group=(UUID)cur.get("document_group_id");
        try{
            jdbc.update("update business_documents set is_current=false where document_group_id=? and is_current=true",group);
            String desc=text(description,1000,false);
            jdbc.update("""
                insert into business_documents(id,entity_type,entity_id,category,title,description,document_date,document_group_id,version_no,is_current,status,
                                               original_name,storage_name,content_type,size_bytes,uploaded_by)
                values(?,?,?,?,?,?,?,?,?,true,'ACTIVE',?,?,?,?,?)
                """,newId,t,cur.get("entity_id"),cur.get("category"),cur.get("title"),desc==null?cur.get("description"):desc,cur.get("document_date"),group,version,stored.originalName(),stored.storageName(),stored.contentType(),stored.sizeBytes(),auth.getName());
        }catch(RuntimeException e){storage.delete(stored.storageName());throw e;}
        audit.record("DOCUMENT_VERSION_UPLOADED","DOCUMENTS",newId.toString(),String.valueOf(cur.get("title"))+" · V"+version+" · "+stored.originalName());
        return required(newId);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_ARCHIVE')")
    public Map<String,Object> archive(@PathVariable UUID id,Authentication auth){
        Map<String,Object> d=required(id);requireScope(auth,String.valueOf(d.get("entity_type")),true);
        if(!Boolean.TRUE.equals(d.get("is_current")))throw new IllegalArgumentException("Solo puede archivarse la versión vigente");
        jdbc.update("update business_documents set status='ARCHIVED',archived_by=?,archived_at=now() where document_group_id=?",auth.getName(),d.get("document_group_id"));
        audit.record("DOCUMENT_ARCHIVED","DOCUMENTS",id.toString(),String.valueOf(d.get("title")));
        return required(id);
    }

    @GetMapping("/{id}/file")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('DOCUMENTS_READ')")
    public ResponseEntity<Resource> file(@PathVariable UUID id,Authentication auth){
        Map<String,Object> d=required(id);requireScope(auth,String.valueOf(d.get("entity_type")),false);Resource r=storage.load(String.valueOf(d.get("storage_name")));
        MediaType mt;try{mt=MediaType.parseMediaType(String.valueOf(d.get("content_type")));}catch(Exception e){mt=MediaType.APPLICATION_OCTET_STREAM;}
        String name=URLEncoder.encode(String.valueOf(d.get("original_name")),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(mt).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+name).contentLength(((Number)d.get("size_bytes")).longValue()).body(r);
    }

    private Map<String,Object> required(UUID id){List<Map<String,Object>> r=jdbc.queryForList("select * from business_documents where id=?",id);if(r.isEmpty())throw new NoSuchElementException("Documento no encontrado");return r.get(0);}
    private Map<String,Object> requiredForUpdate(UUID id){List<Map<String,Object>> r=jdbc.queryForList("select * from business_documents where id=? for update",id);if(r.isEmpty())throw new NoSuchElementException("Documento no encontrado");return r.get(0);}
    private void ensureEntity(String t,UUID id){Integer n=jdbc.queryForObject(switch(t){case "SALES_ORDER"->"select count(*) from sales_orders where id=?";case "PRODUCTION_ORDER"->"select count(*) from production_orders where id=?";case "QUALITY_INSPECTION"->"select count(*) from quality_inspections where id=?";case "DISPATCH"->"select count(*) from dispatches where id=?";default->throw new IllegalArgumentException("Tipo de entidad inválido");},Integer.class,id);if(n==null||n==0)throw new NoSuchElementException("Registro relacionado no encontrado");}
    private String type(String v){String x=v==null?"":v.trim().toUpperCase(Locale.ROOT);if(!TYPES.contains(x))throw new IllegalArgumentException("Tipo documental inválido");return x;}
    private String category(String v){String x=v==null?"OTHER":v.trim().toUpperCase(Locale.ROOT);if(!CATEGORIES.contains(x))throw new IllegalArgumentException("Categoría documental inválida");return x;}
    private String text(String v,int max,boolean required){if(v==null||v.trim().isEmpty()){if(required)throw new IllegalArgumentException("Complete el título del documento");return null;}String x=v.trim();return x.length()<=max?x:x.substring(0,max);}
    private boolean isAdmin(Authentication a){return a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"));}
    private boolean authority(Authentication a,String p){return a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals(p));}
    private boolean hasScope(Authentication a,String t,boolean write){if(isAdmin(a))return true;return switch(t){case "SALES_ORDER"->authority(a,write?"ORDERS_WRITE":"ORDERS_READ");case "PRODUCTION_ORDER"->write?(authority(a,"PRODUCTION_PLANNING")||authority(a,"PRODUCTION_EXECUTE")||authority(a,"PRODUCTION_ACTUALS")):authority(a,"PRODUCTION_ORDERS_READ");case "QUALITY_INSPECTION"->write?(authority(a,"QUALITY_WRITE")||authority(a,"QUALITY_EVIDENCE")):authority(a,"QUALITY_READ");case "DISPATCH"->write?authority(a,"DISPATCH_WRITE"):authority(a,"DISPATCH_READ");default->false;};}
    private void requireScope(Authentication a,String t,boolean write){if(!hasScope(a,t,write))throw new org.springframework.security.access.AccessDeniedException("No tiene permiso sobre el registro relacionado");}
}
