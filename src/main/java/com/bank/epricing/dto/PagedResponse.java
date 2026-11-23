package com.bank.epricing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;

/**
 * PagedResponse — Standardized uniform envelope for paginated and list REST responses.
 *
 * ISSUE-10 FIX: Eliminates polymorphic REST contract flipping between JSON Object and JSON Array.
 * All pricing history endpoints consistently return this root object envelope.
 */
public class PagedResponse<T> {

    @JsonProperty("content")
    private List<T> content;

    @JsonProperty("page")
    private int page;

    @JsonProperty("size")
    private int size;

    @JsonProperty("total_elements")
    private long totalElements;

    @JsonProperty("total_pages")
    private int totalPages;

    @JsonProperty("last")
    private boolean last;

    public PagedResponse() {
        this.content = Collections.emptyList();
    }

    public PagedResponse(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {
        this.content = content != null ? content : Collections.emptyList();
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.last = last;
    }

    public static <T> PagedResponse<T> from(Page<T> page) {
        if (page == null) {
            return new PagedResponse<>(Collections.emptyList(), 0, 0, 0, 0, true);
        }
        return new PagedResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isLast()
        );
    }

    public static <T> PagedResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int effectiveSize = size > 0 ? size : 20;
        int totalPages = (int) Math.ceil((double) totalElements / effectiveSize);
        boolean last = (page + 1) >= totalPages;
        return new PagedResponse<>(content, page, effectiveSize, totalElements, Math.max(1, totalPages), last);
    }

    public List<T> getContent() { return content; }
    public void setContent(List<T> content) { this.content = content; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public boolean isLast() { return last; }
    public void setLast(boolean last) { this.last = last; }
}
