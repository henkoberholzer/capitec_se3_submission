package za.co.capitec.sds.management.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class Document {

    private UUID id;
    private String tokenHash;
    private String storageKey;
    private long fileSizeBytes;
    private String sha256Hash;
    private int maxDownloads;
    private int downloadCount = 0;
    private Instant expiresAt;
    private Instant createdAt = Instant.now();
    private String createdBy;
    private Instant revokedAt;
    private String revokedBy;
    private DocumentStatus status = new DocumentStatus.Active();

    public static Document mapRow(ResultSet rs, int rowNum) throws SQLException {
        Document d = new Document();
        d.id = rs.getObject("id", UUID.class);
        d.tokenHash = rs.getString("token_hash");
        d.storageKey = rs.getString("storage_key");
        d.fileSizeBytes = rs.getLong("file_size_bytes");
        d.sha256Hash = rs.getString("sha256_hash");
        d.maxDownloads = rs.getInt("max_downloads");
        d.downloadCount = rs.getInt("download_count");
        var expiresAt = rs.getTimestamp("expires_at");
        d.expiresAt = expiresAt != null ? expiresAt.toInstant() : null;
        var createdAt = rs.getTimestamp("created_at");
        d.createdAt = createdAt != null ? createdAt.toInstant() : Instant.now();
        d.createdBy = rs.getString("created_by");
        var revokedAt = rs.getTimestamp("revoked_at");
        d.revokedAt = revokedAt != null ? revokedAt.toInstant() : null;
        d.revokedBy = rs.getString("revoked_by");
        d.status = DocumentStatus.fromPersistenceValue(rs.getString("status"));
        return d;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isDownloadable() {
        return status.isDownloadable()
                && !isExpired()
                && downloadCount < maxDownloads;
    }
}
