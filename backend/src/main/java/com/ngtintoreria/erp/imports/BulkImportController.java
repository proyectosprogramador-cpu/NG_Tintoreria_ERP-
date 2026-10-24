package com.ngtintoreria.erp.imports;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/data-import")
public class BulkImportController {
    private final BulkImportService service;
    public BulkImportController(BulkImportService service){this.service=service;}

    @GetMapping("/templates/{type}")
    @PreAuthorize("hasAuthority('DATA_IMPORT_READ')")
    public ResponseEntity<byte[]> template(@PathVariable String type){
        byte[] bytes=service.template(type);
        String name="plantilla_"+type.toLowerCase(Locale.ROOT)+".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20"))
                .body(bytes);
    }

    @PostMapping(value="/preview/{type}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DATA_IMPORT_WRITE')")
    public Map<String,Object> preview(@PathVariable String type,@RequestPart("file") MultipartFile file, Authentication auth){return service.preview(type,file,auth.getName());}

    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('DATA_IMPORT_READ')")
    public List<Map<String,Object>> batches(@RequestParam(defaultValue="50") int limit){return service.batches(limit);}

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAuthority('DATA_IMPORT_READ')")
    public Map<String,Object> batch(@PathVariable UUID id,@RequestParam(defaultValue="250") int rows){return service.batch(id,rows);}

    @PostMapping("/batches/{id}/commit")
    @PreAuthorize("hasAuthority('DATA_IMPORT_WRITE')")
    public Map<String,Object> commit(@PathVariable UUID id,Authentication auth){return service.commit(id,auth.getName());}

    @PostMapping("/batches/{id}/cancel")
    @PreAuthorize("hasAuthority('DATA_IMPORT_WRITE')")
    public Map<String,Object> cancel(@PathVariable UUID id,Authentication auth){service.cancel(id,auth.getName());return Map.of("ok",true);}
}
