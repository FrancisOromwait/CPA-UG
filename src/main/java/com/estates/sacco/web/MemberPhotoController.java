package com.estates.sacco.web;

import com.estates.sacco.service.LedgerService;
import com.estates.sacco.service.MemberPhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;

/** A member's photo: every staff login can look at it, add or change it (PUT, a picture as a data URL) and remove it. */
@RestController
@RequestMapping("/api/members/{id}/photo")
@RequiredArgsConstructor
public class MemberPhotoController {
    private final MemberPhotoService photos;
    private final LedgerService ledgerService;

    /** {@code image}: "data:image/jpeg;base64,...", as a browser gives it, or the plain base64 text. */
    public record PhotoReq(String image) {}

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> get(@PathVariable Long id) {
        ledgerService.requireMember(id);
        return photos.photoOf(id).map(MemberPhotoController::jpeg).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void put(@PathVariable Long id, @RequestBody PhotoReq req) {
        photos.save(ledgerService.requireMember(id), decode(req == null ? null : req.image()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        photos.remove(ledgerService.requireMember(id));
    }

    static ResponseEntity<byte[]> jpeg(byte[] data) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(data);
    }

    static byte[] decode(String image) {
        if (image == null || image.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a photo");
        String b64 = image.contains(",") ? image.substring(image.indexOf(',') + 1) : image;
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That photo could not be read");
        }
    }
}
