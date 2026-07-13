package za.co.capitec.sds.management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.management.domain.AuditEvent;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.repository.AuditEventRepository;
import za.co.capitec.sds.management.repository.DocumentRepository;
import za.co.capitec.sds.management.repository.OutboxEventRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final byte[] MINIMAL_PDF = "%PDF-1.4\n%%EOF\n".getBytes();

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private TokenService tokenService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
            documentRepository,
            auditEventRepository,
            outboxEventRepository,
            storageService,
            tokenService,
            transactionManager
        );
        ReflectionTestUtils.setField(documentService, "maxFileSizeMb", 5);

        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().doNothing().when(transactionManager).commit(any());
        lenient().doNothing().when(transactionManager).rollback(any());
    }

    @Test
    void revoke_whenDocumentNotFound_throwsNotFound() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.revoke(docId, "user1", false))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void revoke_whenNotOwnerAndNotAdmin_throwsForbidden() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document();
        doc.setId(docId);
        doc.setCreatedBy("owner1");
        doc.setStatus(new DocumentStatus.Active());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> documentService.revoke(docId, "other_user", false))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void revoke_whenAlreadyRevoked_isIdempotent() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document();
        doc.setId(docId);
        doc.setCreatedBy("owner1");
        doc.setStatus(new DocumentStatus.Revoked());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        documentService.revoke(docId, "owner1", false);

        verify(documentRepository, never()).save(any());
    }

    @Test
    void revoke_whenActive_changesStatusAndSaves() {
        UUID docId = UUID.randomUUID();
        String callerId = "owner1";
        Document doc = new Document();
        doc.setId(docId);
        doc.setCreatedBy(callerId);
        doc.setStatus(new DocumentStatus.Active());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        documentService.revoke(docId, callerId, false);

        assertThat(doc.getStatus()).isInstanceOf(DocumentStatus.Revoked.class);
        verify(documentRepository).save(doc);
        verify(auditEventRepository).save(any(AuditEvent.class));
    }

    @Test
    void upload_streamsToStorage_thenPromotesWithHashAndSize() throws IOException {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(tokenService.generateToken()).thenReturn("tok12345");
        when(tokenService.hashToken("tok12345")).thenReturn("hash123");
        when(documentRepository.findByTokenHash("hash123")).thenReturn(Optional.empty());

        AtomicReference<Document> saved = new AtomicReference<>();
        doAnswer(inv -> {
            Document d = inv.getArgument(0);
            saved.set(copyDoc(d));
            return null;
        }).when(documentRepository).save(any(Document.class));

        when(documentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            Document d = saved.get();
            return d == null ? Optional.empty() : Optional.of(copyDoc(d));
        });

        // Drain the stream as S3 would — proves we stream, not buffer-then-put from memory array only
        doAnswer(inv -> {
            InputStream in = inv.getArgument(1);
            in.readAllBytes();
            return null;
        }).when(storageService).store(anyString(), any(InputStream.class), anyLong());

        UploadResult result = documentService.upload(
                new ByteArrayInputStream(MINIMAL_PDF),
                2,
                expiresAt,
                "caller-1",
                MINIMAL_PDF.length);

        assertThat(result.token()).isEqualTo("tok12345");
        verify(storageService).store(anyString(), any(InputStream.class), anyLong());
        verify(outboxEventRepository, times(3)).save(any(OutboxEvent.class));
        verify(auditEventRepository).save(any(AuditEvent.class));

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(2)).save(docCaptor.capture());
        Document creating = docCaptor.getAllValues().get(0);
        Document active = docCaptor.getAllValues().get(1);

        assertThat(creating.getStatus()).isInstanceOf(DocumentStatus.Creating.class);
        assertThat(creating.getSha256Hash()).isEmpty();
        assertThat(creating.getFileSizeBytes()).isZero();

        assertThat(active.getStatus()).isInstanceOf(DocumentStatus.Active.class);
        assertThat(active.getSha256Hash()).isNotBlank();
        assertThat(active.getFileSizeBytes()).isEqualTo(MINIMAL_PDF.length);
    }

    @Test
    void upload_whenStoreFails_leavesCreatingWithoutPromote() throws IOException {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(tokenService.generateToken()).thenReturn("tok12345");
        when(tokenService.hashToken("tok12345")).thenReturn("hash123");
        when(documentRepository.findByTokenHash("hash123")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("s3 down")).when(storageService).store(anyString(), any(), anyLong());

        assertThatThrownBy(() -> documentService.upload(
                new ByteArrayInputStream(MINIMAL_PDF),
                2,
                expiresAt,
                "caller-1",
                MINIMAL_PDF.length))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("s3 down");

        verify(documentRepository, times(1)).save(any(Document.class));
        verify(outboxEventRepository, times(3)).save(any(OutboxEvent.class));
        verify(auditEventRepository, never()).save(any());
    }

    @Test
    void upload_whenNotPdf_doesNotPersistOrStore() throws IOException {
        byte[] notPdf = "not-a-pdf".getBytes();

        assertThatThrownBy(() -> documentService.upload(
                new ByteArrayInputStream(notPdf),
                1,
                Instant.now().plusSeconds(60),
                "caller-1",
                notPdf.length))
            .isInstanceOf(za.co.capitec.sds.management.exception.DocumentUploadException.InvalidFileTypeException.class);

        verify(storageService, never()).store(anyString(), any(), anyLong());
        verify(documentRepository, never()).save(any());
        verify(transactionManager, never()).getTransaction(any());
    }

    private static Document copyDoc(Document d) {
        Document copy = new Document();
        copy.setId(d.getId());
        copy.setStatus(d.getStatus());
        copy.setCreatedBy(d.getCreatedBy());
        copy.setTokenHash(d.getTokenHash());
        copy.setStorageKey(d.getStorageKey());
        copy.setFileSizeBytes(d.getFileSizeBytes());
        copy.setSha256Hash(d.getSha256Hash());
        copy.setMaxDownloads(d.getMaxDownloads());
        copy.setExpiresAt(d.getExpiresAt());
        copy.setDownloadCount(d.getDownloadCount());
        return copy;
    }
}
