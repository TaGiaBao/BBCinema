package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.config.AppProperties;
import com.bbcinema.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    @TempDir
    Path dir;

    private FileStorageService service() {
        AppProperties p = TestFixtures.properties();
        AppProperties props = new AppProperties(p.baseUrl(), p.timezone(), p.booking(), p.points(), p.jwt(),
                new AppProperties.Upload(dir.toString()), p.mail(), p.vnpay(), false);
        return new FileStorageService(props);
    }

    @Test
    void storesImageWithRandomName() throws Exception {
        String url = service().storeImage(new MockMultipartFile("poster", "a.PNG", "image/png", new byte[]{1, 2, 3}), "posters");
        assertThat(url).startsWith("/uploads/posters/").endsWith(".png");
        assertThat(Files.size(dir.resolve(url.substring("/uploads/".length())))).isEqualTo(3);
    }

    @Test
    void emptyFileMeansNoChange() {
        assertThat(service().storeImage(new MockMultipartFile("poster", new byte[0]), "posters")).isNull();
        assertThat(service().storeImage(null, "posters")).isNull();
    }

    @Test
    void rejectsNonImages() {
        assertThatThrownBy(() -> service().storeImage(new MockMultipartFile("f", "x.exe", "application/octet-stream", new byte[]{1}), "posters"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().storeImage(new MockMultipartFile("f", "x.png", "text/html", new byte[]{1}), "posters"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> service().storeImage(new MockMultipartFile("f", "x.png", "image/png", new byte[]{1}), "../../etc"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void extension() {
        assertThat(FileStorageService.extensionOf("a.b.JPG")).isEqualTo("jpg");
        assertThat(FileStorageService.extensionOf("noext")).isEmpty();
    }
}
