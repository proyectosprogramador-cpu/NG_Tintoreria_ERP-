package com.ngtintoreria.erp.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class DocumentStorageService {
    private static final long MAX_BYTES=10L*1024*1024;
    private static final Map<String,String> EXTENSIONS=Map.of(
            "application/pdf","pdf",
            "image/jpeg","jpg",
            "image/png","png",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document","docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","xlsx"
    );
    private final Path root;

    public DocumentStorageService(@Value("${app.storage.root:/app/data/files}") String root){
        this.root=Path.of(root).toAbsolutePath().normalize();
        try{Files.createDirectories(this.root);}catch(IOException e){throw new IllegalStateException("No se pudo preparar el almacenamiento documental",e);}
    }

    public StoredFile save(MultipartFile file){
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Seleccione un archivo con contenido");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("El archivo supera el máximo de 10 MB");
        String contentType=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        String ext=EXTENSIONS.get(contentType);
        if(ext==null)throw new IllegalArgumentException("Solo se permiten PDF, JPG, PNG, DOCX o XLSX");
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("documento."+ext);
        original=Path.of(original).getFileName().toString().replaceAll("[\\r\\n]","_");
        String originalExt="";int dot=original.lastIndexOf('.');if(dot>=0&&dot<original.length()-1)originalExt=original.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!originalExt.isBlank()&&!Objects.equals(originalExt,ext)&&!(ext.equals("jpg")&&originalExt.equals("jpeg")))throw new IllegalArgumentException("La extensión del archivo no coincide con su tipo");
        if(original.length()>255)original=original.substring(original.length()-255);
        String storageName="doc-"+UUID.randomUUID()+"."+ext;
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root))throw new IllegalArgumentException("Nombre de archivo inválido");
        try{file.transferTo(target);}catch(IOException e){throw new IllegalStateException("No se pudo guardar el documento",e);}
        return new StoredFile(storageName,original,contentType,file.getSize());
    }

    public void delete(String storageName){
        if(storageName==null||storageName.contains("/")||storageName.contains("\\"))return;
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root))return;
        try{Files.deleteIfExists(target);}catch(IOException ignored){}
    }

    public Resource load(String storageName){
        if(storageName==null||storageName.contains("/")||storageName.contains("\\"))throw new IllegalArgumentException("Archivo inválido");
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root)||!Files.isRegularFile(target))throw new NoSuchElementException("El archivo no está disponible");
        return new FileSystemResource(target);
    }

    public record StoredFile(String storageName,String originalName,String contentType,long sizeBytes){}
}
