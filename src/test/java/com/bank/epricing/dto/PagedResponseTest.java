package com.bank.epricing.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PagedResponseTest {

    @Test
    @DisplayName("Should create PagedResponse from Spring Data Page")
    void testFromPage() {
        List<String> items = List.of("A", "B", "C");
        Page<String> page = new PageImpl<>(items, PageRequest.of(1, 3), 10);

        PagedResponse<String> response = PagedResponse.from(page);
        assertEquals(3, response.getContent().size());
        assertEquals(1, response.getPage());
        assertEquals(3, response.getSize());
        assertEquals(10L, response.getTotalElements());
        assertEquals(4, response.getTotalPages());
        assertFalse(response.isLast());
    }

    @Test
    @DisplayName("Should handle null Page gracefully")
    void testFromNullPage() {
        PagedResponse<String> response = PagedResponse.from(null);
        assertNotNull(response.getContent());
        assertTrue(response.getContent().isEmpty());
        assertEquals(0, response.getTotalElements());
        assertTrue(response.isLast());
    }

    @Test
    @DisplayName("Should create PagedResponse using static factory method of()")
    void testOfFactory() {
        PagedResponse<String> response = PagedResponse.of(List.of("X"), 0, 10, 1);
        assertEquals(1, response.getContent().size());
        assertEquals(0, response.getPage());
        assertEquals(10, response.getSize());
        assertEquals(1, response.getTotalElements());
        assertEquals(1, response.getTotalPages());
        assertTrue(response.isLast());
    }
}
