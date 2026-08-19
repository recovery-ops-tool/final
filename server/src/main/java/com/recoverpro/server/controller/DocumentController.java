package com.recoverpro.server.controller;

import com.recoverpro.server.common.dto.response.ApiResponse;
import com.recoverpro.server.dto.response.CollectionDocumentResponse;
import com.recoverpro.server.entity.CollectionDocument;
import com.recoverpro.server.security.Authz;
import com.recoverpro.server.security.UserPrincipal;
import com.recoverpro.server.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService documentService;

    private static final String READERS = Authz.FO_AND_ADMINS;
    private static final String ADMINS = Authz.ADMINS;

    @PostMapping("/{collectionId}/documents")
    @PreAuthorize("hasAnyRole('FO', 'ORG_ADMIN')")
    public ResponseEntity<ApiResponse<CollectionDocumentResponse>> upload(
            @PathVariable UUID collectionId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        log.info("Upload document: collectionId={}, filename={}", collectionId, file.getOriginalFilename());
        CollectionDocumentResponse response = documentService.uploadDocument(collectionId, file, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("Document uploaded successfully", response));
    }

    @GetMapping("/{collectionId}/documents")
    @PreAuthorize(READERS)
    public ResponseEntity<ApiResponse<List<CollectionDocumentResponse>>> listDocuments(
            @PathVariable UUID collectionId) {
        List<CollectionDocumentResponse> docs = documentService.getDocumentsByCollection(collectionId);
        return ResponseEntity.ok(ApiResponse.success(docs));
    }

    @GetMapping("/{collectionId}/documents/{documentId}/download")
    @PreAuthorize(READERS)
    public ResponseEntity<Resource> download(
            @PathVariable UUID collectionId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Both calls enforce: doc.collectionId == path.collectionId AND caller is in collection's org.
        Resource resource = documentService.downloadDocument(collectionId, documentId, principal.getId());
        CollectionDocument document = documentService.getDocumentForDownload(collectionId, documentId);

        // Spring's ContentDisposition builder handles RFC 5987 encoding
        // (filename*=UTF-8'') and quote-escaping so a malicious original
        // filename like  proof";rm -rf /.pdf  cannot inject header fields.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.getOriginalFilename())
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    @DeleteMapping("/{collectionId}/documents/{documentId}")
    @PreAuthorize(ADMINS)
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID collectionId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        documentService.deleteDocument(collectionId, documentId, principal.getId());
        return ResponseEntity.ok(ApiResponse.of("Document deleted", null));
    }
}