package za.co.capitec.sds.management.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountingInputStreamTest {

    @Test
    void read_countsBytes() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(data), 100);

        stream.read();
        stream.read();
        stream.read();

        assertThat(stream.getBytesRead()).isEqualTo(3);
    }

    @Test
    void readBulk_countsBytes() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(data), 100);

        byte[] buf = new byte[5];
        stream.read(buf, 0, 5);

        assertThat(stream.getBytesRead()).isEqualTo(5);
    }

    @Test
    void read_throwsWhenLimitExceeded() {
        byte[] data = {1, 2, 3};
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(data), 2);

        assertThatThrownBy(() -> {
            stream.read();
            stream.read();
            stream.read();
        }).isInstanceOf(IOException.class)
          .hasMessageContaining("Size limit exceeded");
    }

    @Test
    void readBulk_throwsWhenLimitExceeded() {
        byte[] data = new byte[10];
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(data), 5);

        assertThatThrownBy(() -> {
            byte[] buf = new byte[10];
            stream.read(buf, 0, 10);
        }).isInstanceOf(IOException.class)
          .hasMessageContaining("Size limit exceeded");
    }

    @Test
    void read_exactlyAtLimit_doesNotThrow() throws IOException {
        byte[] data = {1, 2, 3};
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(data), 3);

        stream.read();
        stream.read();
        stream.read();

        assertThat(stream.getBytesRead()).isEqualTo(3);
    }

    @Test
    void read_eof_doesNotCount() throws IOException {
        byte[] data = {1};
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(data), 100);

        stream.read();
        int eof = stream.read();

        assertThat(eof).isEqualTo(-1);
        assertThat(stream.getBytesRead()).isEqualTo(1);
    }

    @Test
    void getBytesRead_startsAtZero() {
        CountingInputStream stream = new CountingInputStream(new ByteArrayInputStream(new byte[0]), 100);
        assertThat(stream.getBytesRead()).isZero();
    }
}
