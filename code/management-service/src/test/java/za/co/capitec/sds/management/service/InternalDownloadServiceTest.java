package za.co.capitec.sds.management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.management.domain.AuditEvent;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.repository.AuditEventRepository;
import za.co.capitec.sds.management.repository.DocumentRepository;
import za.co.capitec.sds.management.repository.DownloadClaimResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalDownloadServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private StorageService storageService;

    private InternalDownloadService service;

    @BeforeEach
    void setUp() {
        service = new InternalDownloadService(
            documentRepository,
            auditEventRepository,
            tokenService,
            storageService
        );
    }

    @Test
    void claimDownload_whenNotFound_throws404() {
        when(tokenService.hashToken("tok")).thenReturn("hash");
        when(documentRepository.claimDownloadSlot("hash"))
                .thenReturn(new DownloadClaimResult.NotFound());

        assertThatThrownBy(() -> service.claimDownload("tok"))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void claimDownload_whenNotAvailable_throws410() {
        when(tokenService.hashToken("tok")).thenReturn("hash");
        when(documentRepository.claimDownloadSlot("hash"))
                .thenReturn(new DownloadClaimResult.NotAvailable());

        assertThatThrownBy(() -> service.claimDownload("tok"))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.GONE);
    }

    @Test
    void claimDownload_whenClaimed_returnsDocument() {
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setStatus(new DocumentStatus.Active());

        when(tokenService.hashToken("tok")).thenReturn("hash");
        when(documentRepository.claimDownloadSlot("hash"))
                .thenReturn(new DownloadClaimResult.Claimed(doc));

        Document result = service.claimDownload("tok");

        assertThat(result).isSameAs(doc);
    }

    @Test
    void openContentStream_delegatesToStorage() {
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setStorageKey("documents/x/payload");
        InputStream expected = new ByteArrayInputStream(new byte[] {1, 2, 3});

        when(storageService.stream("documents/x/payload")).thenReturn(expected);

        assertThat(service.openContentStream(doc)).isSameAs(expected);
    }

    @Test
    void recordDownloadComplete_savesSuccessAudit() {
        UUID docId = UUID.randomUUID();

        service.recordDownloadComplete(docId, "caller-1");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getDocumentId()).isEqualTo(docId);
        assertThat(event.getEventType()).isEqualTo("DOWNLOAD_COMPLETE");
        assertThat(event.getCallerId()).isEqualTo("caller-1");
    }

    @Test
    void releaseDownloadSlot_delegatesToRepository() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.releaseDownloadSlot(docId)).thenReturn(1);

        service.releaseDownloadSlot(docId);

        verify(documentRepository).releaseDownloadSlot(docId);
    }
}
