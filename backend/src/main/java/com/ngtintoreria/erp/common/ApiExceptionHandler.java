package com.ngtintoreria.erp.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException ex) { return body(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException ex) { return body(HttpStatus.NOT_FOUND, "Registro no encontrado"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(DataIntegrityViolationException ex) {
        String detail = rootMessage(ex).toLowerCase();
        String message;
        if(detail.contains("clients_document_number_key") || (detail.contains("clients") && detail.contains("document_number"))){
            message = "El RUC o documento ingresado ya pertenece a otro cliente.";
        } else if(detail.contains("suppliers_ruc_key") || (detail.contains("suppliers") && detail.contains("ruc"))){
            message = "El RUC ingresado ya pertenece a otro proveedor.";
        } else if(detail.contains("fabrics_code_key")){
            message = "El código de tela ingresado ya existe.";
        } else if(detail.contains("colors_code_key")){
            message = "El código de color ingresado ya existe.";
        } else if(detail.contains("processes_code_key")){
            message = "El código de proceso ingresado ya existe.";
        } else if(detail.contains("finishes_code_key")){
            message = "El código de acabado ingresado ya existe.";
        } else if(detail.contains("units_code_key")){
            message = "El código de unidad ingresado ya existe.";
        } else if(detail.contains("machines_code_key")){
            message = "El código de máquina ingresado ya existe.";
        } else if(detail.contains("inventory_items_code_key")){
            message = "El código de insumo ingresado ya existe. Seleccione el insumo del catálogo.";
        } else {
            message = "Existe un dato duplicado o una restricción que impide guardar el registro. Revise los campos marcados como únicos.";
        }
        return body(HttpStatus.CONFLICT, message);
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException ex) { return body(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno al consultar la base de datos"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream().findFirst().map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("Datos inválidos");
        return body(HttpStatus.BAD_REQUEST, msg);
    }
    private static String rootMessage(Throwable ex){
        Throwable root=ex;
        while(root.getCause()!=null) root=root.getCause();
        return root.getMessage()==null?"":root.getMessage();
    }

    private ResponseEntity<?> body(HttpStatus status,String message){
        return ResponseEntity.status(status).body(Map.of(
            "timestamp", OffsetDateTime.now().toString(),
            "status",status.value(),
            "error",status.getReasonPhrase(),
            "message",message==null?"Error":message
        ));
    }
}
