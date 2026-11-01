-- D1 = 随机 7 位 + 唯一索引；D6 = 单表
--
-- 排序规则必须 ascii_bin（大小写敏感）：62 字符集含 a-z 与 A-Z，
-- 用默认的 utf8mb4_0900_ai_ci 会把 'abc1234' 和 'ABC1234' 当成同一行，
-- 表现为随机的唯一索引冲突 + 查错链接。这是本项目最容易踩的坑。

CREATE TABLE IF NOT EXISTS link
(
    code       CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '短码，62 字符集，定长 7',
    long_url   VARCHAR(2048)                                  NOT NULL COMMENT '原样存储，不做任何规范化（AC-1 要求回写完全一致）',
    created_at DATETIME(3)                                    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expire_at  DATETIME(3)                                    NULL COMMENT 'NULL = 永久',
    -- D3-b 的水位：布隆快照落后时用 seq > watermark 做增量补齐
    seq        BIGINT UNSIGNED                                NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (code),
    UNIQUE KEY uk_seq (seq),
    -- D7 定时扫描用
    KEY idx_expire_at (expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='短链映射（单表）';

-- D4：明细保留 3 天，日报表从这里聚合
CREATE TABLE IF NOT EXISTS access_log
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code       CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visited_at DATETIME(3)     NOT NULL,
    visitor    CHAR(36)        NOT NULL COMMENT 'P2：cookie 随机 UUID',
    referer    VARCHAR(512)    NULL,
    PRIMARY KEY (id),
    -- 幂等消费的唯一键：MQ 的 at-least-once 重投不能放大成重复计数。
    -- 这个索引同时覆盖"按短码 + 时间范围"的查询（最左前缀）。
    UNIQUE KEY uk_code_time_visitor (code, visited_at, visitor),
    KEY idx_visited (visited_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='访问明细（AC-7 的"明细总量"）';

CREATE TABLE IF NOT EXISTS link_daily
(
    code      CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stat_date DATE           NOT NULL,
    pv        BIGINT         NOT NULL DEFAULT 0,
    uv        BIGINT         NOT NULL DEFAULT 0 COMMENT 'D5：HyperLogLog 估算值，误差 ≤0.81%',
    PRIMARY KEY (code, stat_date),
    KEY idx_date (stat_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='日报表（AC-7 的"日报表总量"）';

-- D7：过期链接的墓碑。删掉主表行之后，靠它才能继续回答"已过期"（AC-3）。
-- 每行约 24 字节，对比主表 500 字节是 1/20。
CREATE TABLE IF NOT EXISTS link_expired
(
    code        CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expire_at   DATETIME(3) NOT NULL,
    archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (code),
    KEY idx_expire_at (expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='过期短链墓碑（AC-3 用）';

-- 聚合任务的进度游标。必须和 access_log 在同一个持久化域里，
-- 放 Redis 的话重启归零会导致日报表 PV 翻倍且永远修不回来。
CREATE TABLE IF NOT EXISTS job_cursor
(
    name  VARCHAR(32) NOT NULL,
    value BIGINT      NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务的进度游标';
