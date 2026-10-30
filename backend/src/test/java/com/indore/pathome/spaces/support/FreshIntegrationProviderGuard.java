package com.indore.pathome.spaces.support;

import com.cloudinary.Cloudinary;
import com.indore.pathome.spaces.service.CloudinaryService;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Optional;

/**
 * Fail-closed provider replacement for guarded Spring and browser runs.
 *
 * <p>Every method that could reach an external media provider throws before any provider client
 * call. Database outbox/notification persistence remains available and is not delivery proof.</p>
 */
final class FreshIntegrationProviderGuard extends CloudinaryService {
    FreshIntegrationProviderGuard(Cloudinary cloudinary) {
        super(cloudinary);
    }

    @Override
    public String uploadImage(MultipartFile file) {
        return refuse();
    }

    @Override
    public String uploadImage(MultipartFile file, String uploadRequestId) {
        return refuse();
    }

    @Override
    public CloudinaryUploadResult uploadImageResult(MultipartFile file, String uploadRequestId) {
        return refuse();
    }

    @Override
    public String uploadVideo(MultipartFile file) {
        return refuse();
    }

    @Override
    public String uploadVideo(MultipartFile file, String uploadRequestId) {
        return refuse();
    }

    @Override
    public CloudinaryUploadResult uploadVideoResult(MultipartFile file, String uploadRequestId) {
        return refuse();
    }

    @Override
    public CloudinaryUploadResult uploadStreamResult(
            InputStream inputStream, boolean isVideo, String uploadRequestId) {
        return refuse();
    }

    @Override
    public Optional<CloudinaryUploadResult> findExistingResourceByUploadRequestId(
            String uploadRequestId, boolean isVideo) {
        refuse();
        return Optional.empty();
    }

    @Override
    public void deleteResource(String publicId, boolean isVideo) {
        refuse();
    }

    private static <T> T refuse() {
        throw new IllegalStateException(
                "External media provider delivery is disabled in the fresh integration harness");
    }
}
