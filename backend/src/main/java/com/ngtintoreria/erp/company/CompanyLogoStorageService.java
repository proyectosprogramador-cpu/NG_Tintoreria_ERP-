package com.ngtintoreria.erp.company;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class CompanyLogoStorageService {
    private static final long MAX_BYTES=2L*1024*1024;
    private static final Set<String> ALLOWED_TYPES=Set.of("image/jpeg","image/png");
    private static final Map<String,String> EXTENSIONS=Map.of("image/jpeg","jpg","image/png","png");
    private final Path root;

    public CompanyLogoStorageService(@Value("${app.storage.root:/app/data/files}") String root){
        this.root=Path.of(root).toAbsolutePath().normalize();
        try{Files.createDirectories(this.root);}catch(IOException e){throw new IllegalStateException("No se pudo preparar el almacenamiento del logo",e);}
    }

    public StoredLogo save(MultipartFile file){
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Seleccione un logo con contenido");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("El logo supera el máximo de 2 MB");
        String type=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if(!ALLOWED_TYPES.contains(type))throw new IllegalArgumentException("El logo debe ser PNG o JPG");
        try(var in=file.getInputStream()){
            byte[] head=in.readNBytes(8);
            boolean png=head.length>=8&&(head[0]&255)==0x89&&head[1]==0x50&&head[2]==0x4e&&head[3]==0x47&&head[4]==0x0d&&head[5]==0x0a&&head[6]==0x1a&&head[7]==0x0a;
            boolean jpg=head.length>=3&&(head[0]&255)==0xff&&(head[1]&255)==0xd8&&(head[2]&255)==0xff;
            if(("image/png".equals(type)&&!png)||("image/jpeg".equals(type)&&!jpg))throw new IllegalArgumentException("El contenido del archivo no corresponde a una imagen PNG/JPG válida");
        }catch(IOException e){throw new IllegalArgumentException("No se pudo validar el archivo del logo",e);}
        String storageName="company-logo-"+UUID.randomUUID()+"."+EXTENSIONS.get(type);
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root))throw new IllegalArgumentException("Nombre de archivo inválido");
        try{file.transferTo(target);}catch(IOException e){throw new IllegalStateException("No se pudo guardar el logo",e);}
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("logo."+EXTENSIONS.get(type));
        original=Path.of(original).getFileName().toString().replaceAll("[\\r\\n]","_");
        if(original.length()>255)original=original.substring(original.length()-255);
        return new StoredLogo(storageName,original,type,file.getSize());
    }

    public Resource load(String storageName){
        if(storageName==null||storageName.contains("/")||storageName.contains("\\"))throw new NoSuchElementException("El logo no está disponible");
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root)||!Files.isRegularFile(target))throw new NoSuchElementException("El logo no está disponible");
        return new FileSystemResource(target);
    }

    public byte[] readBytes(String storageName){
        if(storageName==null||storageName.isBlank())return null;
        Resource r=load(storageName);
        try{return r.getInputStream().readAllBytes();}catch(IOException e){throw new IllegalStateException("No se pudo leer el logo",e);}
    }

    public void delete(String storageName){
        if(storageName==null||storageName.isBlank()||storageName.contains("/")||storageName.contains("\\"))return;
        Path target=root.resolve(storageName).normalize();
        if(!target.getParent().equals(root))return;
        try{Files.deleteIfExists(target);}catch(IOException e){throw new IllegalStateException("No se pudo eliminar el logo anterior",e);}
    }

    public record StoredLogo(String storageName,String originalName,String contentType,long sizeBytes){}
}
