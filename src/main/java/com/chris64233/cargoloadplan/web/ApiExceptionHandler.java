package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.dto.ErrorResponse;
import com.chris64233.cargoloadplan.service.ConflictException;
import com.chris64233.cargoloadplan.service.LoadConstraintException;
import com.chris64233.cargoloadplan.service.NotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage(), List.of()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ex.getMessage(), List.of()));
    }

    @ExceptionHandler(LoadConstraintException.class)
    public ResponseEntity<ErrorResponse> constraintViolation(LoadConstraintException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ex.getMessage(), ex.getViolations()));
    }

    /** 并发争抢同一货物/舱位或快照竞争：最多一个事务成功，其余得到 409。 */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class,
            DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> concurrencyConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("并发修改冲突，请刷新后重试", List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> badRequest(MethodArgumentNotValidException ex) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("请求参数不合法", violations));
    }
}
