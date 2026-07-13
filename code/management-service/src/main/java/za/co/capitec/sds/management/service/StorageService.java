package za.co.capitec.sds.management.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class StorageService {

    private final S3Client s3Client;

    @Value("${sds.storage.documents-bucket}")
    private String documentsBucket;

    public void store(String key, InputStream data, long contentLength) throws IOException {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(documentsBucket)
                .key(key)
                .contentType("application/pdf")
                .build();

        RequestBody body = contentLength >= 0
                ? RequestBody.fromInputStream(data, contentLength)
                : RequestBody.fromContentProvider(() -> data, "application/pdf");

        s3Client.putObject(request, body);
    }

    public InputStream stream(String key) {
        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(documentsBucket)
                        .key(key)
                        .build());
    }

    public void delete(String key) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(documentsBucket)
                        .key(key)
                        .build());
    }

    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(documentsBucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
