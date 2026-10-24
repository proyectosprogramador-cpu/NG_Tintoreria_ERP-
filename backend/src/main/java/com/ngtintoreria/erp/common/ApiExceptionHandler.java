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
    public ResponseEntity<?> conflict(DataIntegrityViolationException ex) { return body(HttpStatus.CONFLICT, "El registro entra en conflicto con un código o dato único existente"); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException ex) { return body(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno al consultar la base de datos"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream().findFirst().map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("Datos inválidos");
        return body(HttpStatus.BAD_REQUEST, msg);
    }
    private ResponseEntity<?> body(HttpStatus status,String message){return ResponseEntity.status(status).body(Map.of("timestamp", OffsetDateTime.now().toString(),"status",status.value(),"error",status.getReasonPhrase(),"message",message==null?"Error":message));}
}
