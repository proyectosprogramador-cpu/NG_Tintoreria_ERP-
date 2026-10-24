package com.ngtintoreria.erp.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class QualityStorageService {
    private static final long MAX_BYTES=10L*1024*1024;
    private static final Set<String> ALLOWED_TYPES=Set.of("image/jpeg","image/png","application/pdf");
    private static final Map<String,String> EXTENSIONS=Map.of("image/jpeg","jpg","image/png","png","application/pdf","pdf");
    private final Path root;

    public QualityStorageService(@Value("${app.storage.root:/app/data/files}") String root){
        this.root=Path.of(root).toAbsolutePath().normalize();
        try{Files.createDirectories(this.root);}catch(IOException e){throw new IllegalStateException("No se pudo preparar el almacenamiento de calidad",e);}
    }

    public StoredFile save(MultipartFile file){
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Seleccione una evidencia con contenido");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("La evidencia supera el máximo de 10 MB");
        String contentType=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if(!ALLOWED_TYPES.contains(contentType))throw new IllegalArgumentException("Solo se permiten imágenes JPG, PNG o documentos PDF");
        String storageName="quality-"+UUID.randomUUID()+"."+EXTENSIONS.get(contentType);
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root))throw new IllegalArgumentException("Nombre de archivo inválido");
        try{file.transferTo(target);}catch(IOException e){throw new IllegalStateException("No se pudo guardar la evidencia de calidad",e);}
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("evidencia."+EXTENSIONS.get(contentType));
        original=Path.of(original).getFileName().toString().replaceAll("[\\r\\n]","_");
        if(original.length()>255)original=original.substring(original.length()-255);
        return new StoredFile(storageName,original,contentType,file.getSize());
    }

    public Resource load(String storageName){
        if(storageName==null||storageName.contains("/")||storageName.contains("\\"))throw new IllegalArgumentException("Archivo inválido");
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root)||!Files.isRegularFile(target))throw new NoSuchElementException("La evidencia no está disponible");
        return new FileSystemResource(target);
    }

    public void delete(String storageName){
        if(storageName==null||storageName.contains("/")||storageName.contains("\\"))return;
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root))return;
        try{Files.deleteIfExists(target);}catch(IOException e){throw new IllegalStateException("No se pudo eliminar la evidencia",e);}
    }

    public record StoredFile(String storageName,String originalName,String contentType,long sizeBytes){}
}
