package za.co.capitec.sds.management.utils;

import java.io.IOException;
import java.io.InputStream;

public class PrefixedInputStream extends InputStream {

    private final byte[] prefix;
    private int pos = 0;
    private final InputStream delegate;

    public PrefixedInputStream(byte[] prefix, InputStream delegate) {
        this.prefix = prefix;
        this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
        if (pos < prefix.length) {
            return prefix[pos++] & 0xFF;
        }
        return delegate.read();
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (pos < prefix.length) {
            int available = prefix.length - pos;
            int toCopy = Math.min(available, len);
            System.arraycopy(prefix, pos, buf, off, toCopy);
            pos += toCopy;
            return toCopy;
        }
        return delegate.read(buf, off, len);
    }
}
