package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Saves uploaded images (posters, combo pictures, banners) into the upload folder. */
@Service
public class FileStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private final Path root;

    public FileStorageService(AppProperties properties) {
        this.root = Path.of(properties.upload().dir()).toAbsolutePath().normalize();
    }

    public Path getRoot() {
        return root;
    }

    /**
     * Stores the image under {@code <upload dir>/<folder>/} with a random name and returns the
     * public URL, e.g. {@code /uploads/posters/4f1c...png}. Returns null when no file was chosen.
     */
    public String storeImage(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException("Ảnh tối đa 5MB");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("Chỉ nhận ảnh JPG, PNG, WEBP hoặc GIF");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("Tệp tải lên không phải là ảnh");
        }
        String fileName = UUID.randomUUID() + "." + extension;
        Path directory = root.resolve(folder).normalize();
        if (!directory.startsWith(root)) {
            throw new BusinessException("Thư mục lưu ảnh không hợp lệ");
        }
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(directory);
            Files.copy(in, directory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("Không lưu được ảnh: " + e.getMessage());
        }
        return "/uploads/" + folder + "/" + fileName;
    }

    static String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
