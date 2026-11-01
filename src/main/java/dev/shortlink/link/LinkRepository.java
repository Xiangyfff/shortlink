package dev.shortlink.link;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * D6 = 单表。code 是聚簇主键，一次点查 = 一次 B+ 树查找。
 */
@Repository
public class LinkRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final RowMapper<Link> MAPPER = (rs, rowNum) -> new Link(
            rs.getString("code"),
            rs.getString("long_url"),
            toInstant(rs.getObject("created_at", LocalDateTime.class)),
            toInstant(rs.getObject("expire_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public LinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 墓碑表。放在这里建而不是只放 init 脚本里，是为了让"已经有数据卷"的环境也能跑起来
     * （init 脚本只在空库时执行一次）。
     */
    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS link_expired (
                    code        CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    expire_at   DATETIME(3) NOT NULL,
                    archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (code),
                    KEY idx_expire_at (expire_at)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='过期短链墓碑（AC-3 用）'
                """);
    }

    /**
     * 不做"先 SELECT 再 INSERT"：两步之间有竞态，真正保证唯一的是主键。
     * 碰撞时抛 DuplicateKeyException，由调用方重试。
     */
    public void insert(Link link) {
        jdbc.update(
                "INSERT INTO link (code, long_url, created_at, expire_at) VALUES (?, ?, ?, ?)",
                link.code(),
                link.longUrl(),
                LocalDateTime.ofInstant(link.createdAt(), ZONE),
                link.expireAt() == null ? null : LocalDateTime.ofInstant(link.expireAt(), ZONE));
    }

    public Link findByCode(String code) {
        List<Link> rows = jdbc.query(
                "SELECT code, long_url, created_at, expire_at FROM link WHERE code = ?",
                MAPPER,
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 主表没查到之后，再问墓碑表。回答"是"→ 返回过期页（AC-3）；
     * 回答"否"→ 才是真的不存在（FR-2）。
     */
    public Instant findArchivedExpireAt(String code) {
        List<LocalDateTime> rows = jdbc.queryForList(
                "SELECT expire_at FROM link_expired WHERE code = ?", LocalDateTime.class, code);
        return rows.isEmpty() ? null : toInstant(rows.get(0));
    }

    public List<Link> findExpiredBatch(int limit) {
        return jdbc.query("""
                SELECT code, long_url, created_at, expire_at
                FROM link
                WHERE expire_at IS NOT NULL AND expire_at <= ?
                ORDER BY expire_at
                LIMIT ?
                """, MAPPER, LocalDateTime.now(ZONE), limit);
    }

    public void archive(List<Link> links) {
        jdbc.batchUpdate(
                "INSERT IGNORE INTO link_expired (code, expire_at) VALUES (?, ?)",
                links,
                links.size(),
                (ps, link) -> {
                    ps.setString(1, link.code());
                    ps.setObject(2, LocalDateTime.ofInstant(link.expireAt(), ZONE));
                });
    }

    public void deleteByCodes(List<String> codes) {
        jdbc.batchUpdate("DELETE FROM link WHERE code = ?", codes, codes.size(),
                (ps, code) -> ps.setString(1, code));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }
}
