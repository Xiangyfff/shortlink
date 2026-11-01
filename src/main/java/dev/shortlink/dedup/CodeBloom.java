package dev.shortlink.dedup;

import dev.shortlink.config.ShortlinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * D3③ 布隆过滤器 + D3-b 位图落盘。
 * <p>
 * 关键不变量：<b>绝不产生假阴性</b>。假阳性只是多查一次库，假阴性会让有效短链变 404。
 * 为此有三道保险：
 * <ol>
 *   <li>创建路径"先 add 后 insert"——提交成功的短码，布隆里必然已经有它；</li>
 *   <li>快照里带 watermark（落盘时刻的 MAX(seq)），重启后补扫 seq &gt; watermark；</li>
 *   <li>补齐完成前 {@code ready=false}，布隆"说不存在"时不采信，直接放行去查库。</li>
 * </ol>
 */
@Component
public class CodeBloom {

    private static final Logger log = LoggerFactory.getLogger(CodeBloom.class);

    private static final int MAGIC = 0x534C4246; // "SLBF"
    private static final int VERSION = 1;
    /** 头部：magic + version + m + k + watermark + byteLength */
    private static final int HEADER_BYTES = 4 + 4 + 8 + 4 + 8 + 4;

    private final AtomicLongArray bits;
    private final long bitCount;
    private final int hashes;
    private final Path snapshotPath;

    /** 落盘水位：位图快照里"包含到哪一条 seq"。0 表示还没落过盘。 */
    private volatile long watermark;
    /** 补齐是否完成。false 时不要相信"不存在"的判断。 */
    private volatile boolean ready;

    private final JdbcTemplate jdbc;
    private final long dumpDelayMs;

    public CodeBloom(ShortlinkProperties props, JdbcTemplate jdbc) {
        ShortlinkProperties.Bloom cfg = props.bloom();
        this.bitCount = cfg.bits();
        this.hashes = cfg.hashes();
        this.snapshotPath = Path.of(cfg.path());
        this.dumpDelayMs = cfg.dumpIntervalMs();
        this.jdbc = jdbc;
        this.bits = new AtomicLongArray((int) (bitCount / 64));

        if (loadSnapshot()) {
            log.info("bloom snapshot loaded: path={}, bits={}, watermark={}", snapshotPath, bitCount, watermark);
        } else {
            log.info("bloom snapshot absent, will rebuild from mysql: path={}", snapshotPath);
        }
    }

    public boolean mightContain(String code) {
        long h1 = hash64(code, 0x9E3779B97F4A7C15L);
        long h2 = hash64(code, 0xC2B2AE3D27D4EB4FL) | 1L;
        for (int i = 0; i < hashes; i++) {
            long idx = Math.floorMod(h1 + (long) i * h2, bitCount);
            long word = bits.get((int) (idx >>> 6));
            if ((word & (1L << (idx & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 必须在 insert 之前调用（先 add 后 insert）。提交失败只会留下一个无害的假阳性位。
     */
    public void add(String code) {
        long h1 = hash64(code, 0x9E3779B97F4A7C15L);
        long h2 = hash64(code, 0xC2B2AE3D27D4EB4FL) | 1L;
        for (int i = 0; i < hashes; i++) {
            long idx = Math.floorMod(h1 + (long) i * h2, bitCount);
            int wordIndex = (int) (idx >>> 6);
            long mask = 1L << (idx & 63);
            long oldValue;
            long newValue;
            do {
                oldValue = bits.get(wordIndex);
                if ((oldValue & mask) != 0) {
                    break;
                }
                newValue = oldValue | mask;
            } while (!bits.compareAndSet(wordIndex, oldValue, newValue));
        }
    }

    public boolean ready() {
        return ready;
    }

    /**
     * 启动后补齐：只扫 seq &gt; watermark 的增量，通常几百到几十万行。
     * 首次启动（水位 0）等于全表扫，这是快照丢失时的兜底路径。
     */
    public void backfill() {
        long started = System.currentTimeMillis();
        long scanned = 0;
        final long from = watermark;
        try {
            scanned = jdbc.query(
                    con -> {
                        var ps = con.prepareStatement("SELECT code FROM link WHERE seq > ?");
                        ps.setFetchSize(2000);
                        ps.setLong(1, from);
                        return ps;
                    },
                    rs -> {
                        long n = 0;
                        while (rs.next()) {
                            add(rs.getString(1));
                            n++;
                        }
                        return n;
                    });
            ready = true;
            log.info("bloom backfill done: fromSeq={}, scanned={}, cost={}ms", from, scanned,
                    System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            // 补齐失败就保持 ready=false：布隆只说"是"，不说"否"，系统仍能正确服务。
            log.error("bloom backfill failed, stay in ready=false (bloom will not answer 'no')", e);
        }
    }

    @Scheduled(fixedDelayString = "${shortlink.bloom.dump-interval-ms:300000}", initialDelay = 60_000)
    public void dump() {        long newWatermark = watermark;
        try {
            Long maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM link", Long.class);
            if (maxSeq != null) {
                newWatermark = maxSeq;
            }
        } catch (RuntimeException e) {
            log.warn("read MAX(seq) failed, dump with previous watermark={}", watermark, e);
        }

        Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(snapshotPath.getParent());
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            header.putInt(MAGIC).putInt(VERSION).putLong(bitCount).putInt(hashes)
                    .putLong(newWatermark).putInt((int) (bitCount / 8));
            header.flip();

            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(header);
                ByteBuffer chunk = ByteBuffer.allocate(1 << 20);
                for (int i = 0; i < bits.length(); i++) {
                    if (chunk.remaining() < Long.BYTES) {
                        chunk.flip();
                        while (chunk.hasRemaining()) {
                            ch.write(chunk);
                        }
                        chunk.clear();
                    }
                    chunk.putLong(bits.get(i));
                }
                chunk.flip();
                while (chunk.hasRemaining()) {
                    ch.write(chunk);
                }
                ch.force(true);
            }
            // 原子替换：进程在写一半时崩，旧快照仍然完整可用
            Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            watermark = newWatermark;
            log.info("bloom snapshot dumped: watermark={}", newWatermark);
        } catch (IOException e) {
            log.error("bloom snapshot dump failed", e);
        }
    }

    /**
     * 优雅停机时补落一次盘。docker stop 会发 SIGTERM，Spring 会走销毁回调，
     * 于是 compose restart / down 这类日常操作**永远不会**退化成全表重建。
     * 只有 kill -9、OOM、掉电才会退回上一个 5 分钟快照。
     */
    @jakarta.annotation.PreDestroy
    public void dumpOnShutdown() {
        try {
            dump();
        } catch (RuntimeException e) {
            log.warn("shutdown dump failed (fallback: rebuild from mysql on next start)", e);
        }
    }

    private boolean loadSnapshot() {
        if (!Files.exists(snapshotPath)) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(snapshotPath, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            while (header.hasRemaining()) {
                if (ch.read(header) < 0) {
                    return false;
                }
            }
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != VERSION) {
                return false;
            }
            long m = header.getLong();
            int k = header.getInt();
            long wm = header.getLong();
            int byteLen = header.getInt();
            // 参数变了就不认旧快照，退回全量重建
            if (m != bitCount || k != hashes || byteLen != bitCount / 8) {
                log.warn("bloom snapshot mismatch (m={},k={},len={}), rebuild", m, k, byteLen);
                return false;
            }
            ByteBuffer chunk = ByteBuffer.allocate(1 << 20);
            int wordIndex = 0;
            long remainingBytes = byteLen;
            while (remainingBytes > 0) {
                chunk.clear();
                chunk.limit((int) Math.min(chunk.capacity(), remainingBytes));
                while (chunk.hasRemaining()) {
                    if (ch.read(chunk) < 0) {
                        return false;
                    }
                }
                chunk.flip();
                while (chunk.remaining() >= Long.BYTES) {
                    bits.set(wordIndex++, chunk.getLong());
                }
                remainingBytes -= chunk.limit();
            }
            watermark = wm;
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("bloom snapshot load failed, rebuild from mysql", e);
            return false;
        }
    }

    private static long hash64(String s, long seed) {
        long h = 0xcbf29ce484222325L ^ seed;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
