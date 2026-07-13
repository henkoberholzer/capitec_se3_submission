package za.co.capitec.sds.management.utils;

import java.io.IOException;
import java.io.InputStream;

public class CountingInputStream extends InputStream {

    private final InputStream delegate;
    private final long maxBytes;
    private long bytesRead = 0;

    public CountingInputStream(InputStream delegate, long maxBytes) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = delegate.read();
        if (b != -1) {
            checkLimit(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = delegate.read(buf, off, len);
        if (n > 0) {
            checkLimit(n);
        }
        return n;
    }

    private void checkLimit(int n) throws IOException {
        bytesRead += n;
        if (bytesRead > maxBytes) {
            throw new SizeLimitExceededException();
        }
    }

    public long getBytesRead() {
        return bytesRead;
    }

    public static class SizeLimitExceededException extends IOException {
        public SizeLimitExceededException() {
            super("Size limit exceeded");
        }
    }
}
