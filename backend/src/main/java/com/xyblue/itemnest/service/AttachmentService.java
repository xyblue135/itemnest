package com.xyblue.itemnest.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final Path root;

    public AttachmentService(JdbcTemplate jdbc, Path itemNestDataDir) throws IOException {
        this.jdbc = jdbc;
        this.root = itemNestDataDir.resolve("attachments");
        Files.createDirectories(root);
    }

    public List<Map<String, Object>> list(long itemId) {
        return jdbc.queryForList("SELECT id,item_id,kind,filename,mime_type,size_bytes,created_at FROM attachments WHERE item_id=? ORDER BY id DESC", itemId);
    }

    public List<Map<String, Object>> snapshot(long itemId) {
        return jdbc.queryForList("SELECT id,item_id,kind,filename,stored_name,mime_type,size_bytes,created_at FROM attachments WHERE item_id=? ORDER BY id DESC", itemId);
    }

    public Map<String, Object> save(long itemId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("附件为空");
        if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("单个附件不能超过 20MB");
        String original = sanitizeFilename(file.getOriginalFilename());
        String ext = extension(original);
        String stored = "item-" + itemId + "/" + UUID.randomUUID() + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法附件路径");
        Files.createDirectories(target.getParent());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String kind = mime.startsWith("image/") ? "image" : "file";
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO attachments(item_id,kind,filename,stored_name,mime_type,size_bytes) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, itemId); ps.setString(2, kind); ps.setString(3, original); ps.setString(4, stored);
            ps.setString(5, mime); ps.setLong(6, file.getSize()); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("附件元数据写入失败");
        return get(id.longValue());
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM attachments WHERE id=?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Resource resource(long id) throws IOException {
        Map<String, Object> meta = get(id);
        if (meta == null) return null;
        Path path = root.resolve(String.valueOf(meta.get("stored_name"))).normalize();
        if (!path.startsWith(root) || !Files.exists(path)) return null;
        return new UrlResource(path.toUri());
    }

    public boolean delete(long id) throws IOException {
        Map<String, Object> meta = get(id);
        if (meta == null) return false;
        Path path = root.resolve(String.valueOf(meta.get("stored_name"))).normalize();
        jdbc.update("DELETE FROM attachments WHERE id=?", id);
        if (path.startsWith(root)) Files.deleteIfExists(path);
        return true;
    }

    private static String sanitizeFilename(String value) {
        String name = value == null || value.isBlank() ? "attachment" : Path.of(value).getFileName().toString();
        return name.replaceAll("[\\r\\n\\t]", "_").replaceAll("[\\\\/:*?\"<>|]", "_");
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length()-1) return "";
        String ext = name.substring(dot).toLowerCase();
        return ext.length() <= 12 ? ext : "";
    }
}
