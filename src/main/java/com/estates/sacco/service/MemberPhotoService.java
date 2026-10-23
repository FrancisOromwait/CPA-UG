package com.estates.sacco.service;

import com.estates.sacco.domain.Member;
import com.estates.sacco.domain.MemberPhoto;
import com.estates.sacco.repo.MemberPhotoRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Members' photos. Any staff login may add, change or remove one (recorded in the audit trail). Whatever picture is sent is read as
 * an image (anything else is refused), made at most 400 pixels a side and stored as a JPEG, so a photo takes some tens of kilobytes.
 */
@Service
@RequiredArgsConstructor
public class MemberPhotoService {
    /** The longest side of a stored photo, in pixels. */
    static final int SIZE = 400;
    /** Bigger uploads are refused before they are read. */
    static final int MAX_UPLOAD = 8 * 1024 * 1024;

    private final MemberPhotoRepository photos;
    private final AuditService audit;
    private final BranchScope scope;

    public Optional<byte[]> photoOf(Long memberId) {
        return photos.findByMemberId(memberId).map(MemberPhoto::getData);
    }

    @Transactional
    public void save(Member member, byte[] upload) {
        byte[] jpeg = shrink(upload);
        MemberPhoto p = photos.findByMemberId(member.getId()).orElse(null);
        boolean had = p != null;
        if (p == null) {
            p = new MemberPhoto();
            p.setMember(member);
        }
        p.setData(jpeg);
        p.setUpdatedBy(scope.me().getUsername());
        p.setUpdatedAt(Instant.now());
        photos.save(p);
        audit.log(member.getBranch(), "EDIT", "Member photo", member.getId(),
                (had ? "Photo changed for " : "Photo added for ") + member.getMemberNo() + " " + member.getFullName());
    }

    @Transactional
    public void remove(Member member) {
        if (photos.findByMemberId(member.getId()).isEmpty()) return;
        photos.deleteByMemberId(member.getId());
        audit.log(member.getBranch(), "DELETE", "Member photo", member.getId(), "Photo removed for " + member.getMemberNo() + " " + member.getFullName());
    }

    /** Reads the picture and writes it again as a JPEG of at most SIZE pixels a side, on white (for pictures with see-through parts). */
    static byte[] shrink(byte[] upload) {
        if (upload == null || upload.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a photo");
        if (upload.length > MAX_UPLOAD) throw new ApiException(HttpStatus.BAD_REQUEST, "The photo is too big (at most 8 MB)");
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(upload));
        } catch (IOException e) {
            src = null;
        }
        if (src == null) throw new ApiException(HttpStatus.BAD_REQUEST, "That file is not a picture this system can read (use JPEG or PNG)");
        double scale = Math.min(1.0, (double) SIZE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale)), h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), param);
            writer.dispose();
            ios.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the photo");
        }
    }
}
