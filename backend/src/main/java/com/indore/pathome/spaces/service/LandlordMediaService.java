package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.exception.DraftConflictException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class LandlordMediaService {
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");
    private final LandlordMediaStore store;
    private final CloudinaryService cloudinary;

    public LandlordMediaService(LandlordMediaStore store, CloudinaryService cloudinary) {
        this.store = store;
        this.cloudinary = cloudinary;
    }

    public List<LandlordMediaItem> list(String email, String draftId) { return store.list(email, draftId); }

    public LandlordMediaItem upload(String email, String draftId, String mediaId, MultipartFile file) {
        String cleanId = requireUuid(mediaId);
        String contentType = validateFile(file);
        String name = safeFilename(file.getOriginalFilename());
        LandlordMediaStore.Claim claim = store.claim(email, draftId, cleanId, name, contentType, file.getSize());
        if ("UPLOADED".equals(claim.media().getUploadStatus())) return store.list(email, draftId).stream()
                .filter(item -> item.mediaId().equals(cleanId)).findFirst().orElseThrow();
        String requestId = requestId(claim.media());
        boolean video = contentType.startsWith("video/");
        try {
            var existing = claim.retry()
                    ? cloudinary.findExistingResourceByUploadRequestId(requestId, video)
                    : java.util.Optional.<CloudinaryService.CloudinaryUploadResult>empty();
            var result = existing.orElseGet(() -> video
                    ? cloudinary.uploadVideoResult(file, requestId)
                    : cloudinary.uploadImageResult(file, requestId));
            return store.complete(email, draftId, cleanId, result);
        } catch (RuntimeException ex) {
            // The claim survives a failed upload or failed DB completion for deterministic recovery.
            try { store.markFailed(email, draftId, cleanId); }
            catch (RuntimeException failure) { ex.addSuppressed(failure); }
            throw ex;
        }
    }

    public LandlordMediaItem recover(String email, String draftId, String mediaId) {
        PropertyDraftMedia item = store.recoverable(email, draftId, requireUuid(mediaId));
        if ("UPLOADED".equals(item.getUploadStatus())) return store.list(email, draftId).stream()
                .filter(current -> current.mediaId().equals(mediaId)).findFirst().orElseThrow();
        if ("DELETING".equals(item.getUploadStatus())) throw new DraftConflictException(draftId, 0, "Media is being removed");
        boolean video = item.getContentType().startsWith("video/");
        var found = cloudinary.findExistingResourceByUploadRequestId(requestId(item), video);
        if (found.isEmpty()) {
            store.markFailed(email, draftId, mediaId);
            throw new DraftConflictException(draftId, 0, "The original file is needed to retry this upload");
        }
        return store.complete(email, draftId, mediaId, found.get());
    }

    public List<LandlordMediaItem> makeCover(String email, String draftId, String mediaId) {
        return store.makeCover(email, draftId, requireUuid(mediaId));
    }

    public List<LandlordMediaItem> reorder(String email, String draftId, List<String> ids) {
        if (ids == null) throw new IllegalArgumentException("Media order is required");
        return store.reorder(email, draftId, ids.stream().map(this::requireUuid).toList());
    }

    public void delete(String email, String draftId, String mediaId) {
        PropertyDraftMedia item = store.markDeleting(email, draftId, requireUuid(mediaId));
        boolean video = item.getContentType().startsWith("video/");
        String publicId = item.getCloudinaryPublicId();
        if (publicId == null || publicId.isBlank()) {
            publicId = cloudinary.findExistingResourceByUploadRequestId(requestId(item), video)
                    .map(CloudinaryService.CloudinaryUploadResult::publicId).orElse(null);
        }
        cloudinary.deleteResource(publicId, video);
        store.finishDeleting(email, draftId, mediaId);
    }

    private String requestId(PropertyDraftMedia item) {
        return "lessor-" + item.getLandlordUserId() + "-" + item.getMediaId().replace("-", "");
    }

    private String requireUuid(String raw) {
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid media request ID"); }
    }

    private String safeFilename(String raw) {
        String name = raw == null ? "property-media" : raw.replace('\\', '/');
        name = CONTROL_CHARACTERS.matcher(name.substring(name.lastIndexOf('/') + 1)).replaceAll("").trim();
        return name.isEmpty() ? "property-media" : name.substring(0, Math.min(name.length(), 255));
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a nonempty photo or video");
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean image = List.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif").contains(mime);
        boolean video = List.of("video/mp4", "video/quicktime").contains(mime);
        if (!image && !video) throw new IllegalArgumentException("Choose a supported photo or video format");
        if (file.getSize() > (video ? CloudinaryService.MAX_VIDEO_BYTES : CloudinaryService.MAX_IMAGE_BYTES)) {
            throw new IllegalArgumentException(video ? "Video must be 100 MB or smaller" : "Photo must be 10 MB or smaller");
        }
        byte[] header;
        try (InputStream input = file.getInputStream()) {
            header = input.readNBytes(16);
            if (header.length < 12) throw new IllegalArgumentException("File could not be verified");
        } catch (IOException ex) { throw new IllegalArgumentException("File could not be read"); }
        boolean jpeg = (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
        boolean png = header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
        boolean webp = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
        String box = new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        boolean heif = box.equals("ftyp") && (brand.startsWith("hei") || brand.startsWith("mif"));
        boolean movie = box.equals("ftyp") && !heif;
        if (image && !(mime.equals("image/jpeg") && jpeg || mime.equals("image/png") && png
                || mime.equals("image/webp") && webp || (mime.equals("image/heic") || mime.equals("image/heif")) && heif)
                || video && !movie) {
            throw new IllegalArgumentException("File content does not match its format");
        }
        return mime;
    }
}
