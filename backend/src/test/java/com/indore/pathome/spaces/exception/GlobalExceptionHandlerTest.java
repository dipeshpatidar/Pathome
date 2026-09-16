package com.indore.pathome.spaces.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        exceptionHandler = new GlobalExceptionHandler();
        when(request.getRequestURI()).thenReturn("/api/v1/test");
    }

    @Test
    public void testHandleEntityNotFound_Returns404() {
        EntityNotFoundException ex = new EntityNotFoundException("Property not found");
        ResponseEntity<ErrorResponseDTO> response = exceptionHandler.handleEntityNotFound(ex, request);

        assertNotNull(response);
        assertEquals(404, response.getStatusCode().value());
        assertEquals("Resource Not Found", response.getBody().getError());
        assertEquals("Property not found", response.getBody().getMessage());
        assertEquals("/api/v1/test", response.getBody().getPath());
    }

    @Test
    public void testHandleIllegalArgument_Returns400() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid input payload");
        ResponseEntity<ErrorResponseDTO> response = exceptionHandler.handleIllegalArgument(ex, request);

        assertNotNull(response);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("Bad Request", response.getBody().getError());
        assertEquals("Invalid input payload", response.getBody().getMessage());
    }

    @Test
    public void testHandleGenericException_Returns500() {
        RuntimeException ex = new RuntimeException("Database connection timeout");
        ResponseEntity<ErrorResponseDTO> response = exceptionHandler.handleGenericException(ex, request);

        assertNotNull(response);
        assertEquals(500, response.getStatusCode().value());
        assertEquals("Internal Server Error", response.getBody().getError());
        assertEquals(
                "We could not complete this request right now. Please try again shortly.",
                response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().contains("Database"));
    }
}
