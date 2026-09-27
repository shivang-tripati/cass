package com.shivang.obd.common.api.scaffold;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestApiController {

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ResponseFactory.ok("Backend Foundation Working"));
    }

    @GetMapping("/object")
    public ResponseEntity<ApiResponse<TestItemDto>> object() {
        return ResponseEntity.ok(ResponseFactory.ok(new TestItemDto("1", "first")));
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<TestItemDto>>> list() {
        return ResponseEntity.ok(ResponseFactory.ok(List.of(new TestItemDto("1", "first"), new TestItemDto("2", "second"))));
    }

    @GetMapping("/page")
    public ResponseEntity<ApiResponse<List<TestItemDto>>> page() {
        PaginationMetadata pagination = PaginationMetadata.of(2, 20, 245);
        List<TestItemDto> data = List.of(new TestItemDto("41", "item-41"), new TestItemDto("42", "item-42"));
        return ResponseEntity.ok(ResponseFactory.page(data, pagination));
    }

    @PostMapping("/created")
    public ResponseEntity<ApiResponse<TestItemDto>> created() {
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseFactory.created(new TestItemDto("9", "created")));
    }

    @PostMapping("/validation")
    public ResponseEntity<ApiResponse<String>> validation(@Valid @RequestBody ValidationTestRequest request) {
        return ResponseEntity.ok(ResponseFactory.ok("Validation passed"));
    }

    @GetMapping("/type-mismatch")
    public ResponseEntity<ApiResponse<String>> typeMismatch(@RequestParam Long id) {
        return ResponseEntity.ok(ResponseFactory.ok("id=" + id));
    }

    @GetMapping("/constraint-param")
    public ResponseEntity<ApiResponse<String>> constraintParam(@RequestParam @Email String email) {
        return ResponseEntity.ok(ResponseFactory.ok(email));
    }

    @GetMapping("/not-found")
    public void notFound() {
        throw new ResourceNotFoundException("Requested resource not found");
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new ConflictException("Resource state conflicts with current operation");
    }

    @GetMapping("/unauthorized")
    public void unauthorized() {
        throw new InsufficientAuthenticationException("Authentication required");
    }

    @GetMapping("/forbidden")
    public void forbidden() {
        throw new AccessDeniedException("Access is denied");
    }

    @GetMapping("/unexpected")
    public void unexpected() {
        throw new IllegalStateException("jdbc:postgresql://secret-db/hunter2 leaked");
    }
}
