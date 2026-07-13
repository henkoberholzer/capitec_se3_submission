package za.co.capitec.sds.management.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PrefixedInputStreamTest {

    @Test
    void read_prefixThenDelegate() throws IOException {
        byte[] prefix = {1, 2, 3};
        byte[] rest = {4, 5, 6};
        PrefixedInputStream stream = new PrefixedInputStream(prefix, new ByteArrayInputStream(rest));

        assertThat(stream.read()).isEqualTo(1);
        assertThat(stream.read()).isEqualTo(2);
        assertThat(stream.read()).isEqualTo(3);
        assertThat(stream.read()).isEqualTo(4);
        assertThat(stream.read()).isEqualTo(5);
        assertThat(stream.read()).isEqualTo(6);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void readBulk_prefixThenDelegate() throws IOException {
        byte[] prefix = {1, 2};
        byte[] rest = {3, 4, 5};
        PrefixedInputStream stream = new PrefixedInputStream(prefix, new ByteArrayInputStream(rest));

        byte[] buf = new byte[5];
        int read = stream.read(buf, 0, 5);

        assertThat(read).isEqualTo(2);
        assertThat(buf[0]).isEqualTo((byte) 1);
        assertThat(buf[1]).isEqualTo((byte) 2);

        read = stream.read(buf, 0, 5);
        assertThat(read).isEqualTo(3);
        assertThat(buf[0]).isEqualTo((byte) 3);
        assertThat(buf[1]).isEqualTo((byte) 4);
        assertThat(buf[2]).isEqualTo((byte) 5);
    }

    @Test
    void read_emptyPrefix_readsDirectlyFromDelegate() throws IOException {
        byte[] prefix = {};
        byte[] rest = {10, 20};
        PrefixedInputStream stream = new PrefixedInputStream(prefix, new ByteArrayInputStream(rest));

        assertThat(stream.read()).isEqualTo(10);
        assertThat(stream.read()).isEqualTo(20);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void read_emptyDelegate_onlyReturnsPrefixBytes() throws IOException {
        byte[] prefix = {7, 8};
        PrefixedInputStream stream = new PrefixedInputStream(prefix, new ByteArrayInputStream(new byte[0]));

        assertThat(stream.read()).isEqualTo(7);
        assertThat(stream.read()).isEqualTo(8);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void read_highByteValues_notSignExtended() throws IOException {
        byte[] prefix = {(byte) 0xFF, (byte) 0x80};
        PrefixedInputStream stream = new PrefixedInputStream(prefix, new ByteArrayInputStream(new byte[0]));

        assertThat(stream.read()).isEqualTo(0xFF);
        assertThat(stream.read()).isEqualTo(0x80);
    }
}
