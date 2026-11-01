-- 压测数据准备：把 link 表灌到 1000 万行（AC-5 的前提）。
--
-- 用法：
--   docker exec -i shortlink-mysql mysql -uroot -proot shortlink < scripts/load-data.sql
--
-- 重复执行是安全的：link 表非空时会直接跳过（不会重复灌）。
-- 灌一次在本机约 80 分钟——随机主键插入在 1000 万规模下只有 1500–2100 行/秒，
-- 越写越慢（页分裂 + buffer pool 颠簸）。这个数字本身就是 D1 取舍的实测证据。

DELIMITER $$

DROP PROCEDURE IF EXISTS bench_load $$

CREATE PROCEDURE bench_load(IN target_rows BIGINT)
BEGIN
    DECLARE existing BIGINT DEFAULT 0;
    DECLARE level INT DEFAULT 0;
    DECLARE offset BIGINT DEFAULT 1;

    SELECT COUNT(*) INTO existing FROM link;
    IF existing > 1000 THEN
        SELECT CONCAT('skip: link 已有 ', existing, ' 行，不重复灌') AS msg;
    ELSE
        -- 1) 号码表：用倍增法填到 2^24 = 16,777,216 行（比逐行插快两个数量级）
        DROP TABLE IF EXISTS nums;
        CREATE TABLE nums (n BIGINT UNSIGNED PRIMARY KEY) ENGINE = InnoDB;
        INSERT INTO nums VALUES (0);

        WHILE level < 24 DO
            SET @off = offset;
            SET @sql = CONCAT('INSERT INTO nums SELECT n + ', @off, ' FROM nums');
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
            SET offset = offset * 2;
            SET level = level + 1;
        END WHILE;

        -- 2) 生成短码：m = (n * K) MOD 62^7，K 与 62^7 互质 → 是一个置换，保证短码不重复。
        --    不用连续码，是为了让主键分布接近真实随机码（碎片、页分裂都更真实）。
        --    长 URL 填充到约 200 字节，让行宽和索引体积接近需求 §7 的假设。
        SET @alpha = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
        SET @target = target_rows;

        INSERT INTO link (code, long_url, created_at, expire_at)
        SELECT CONCAT(
                       SUBSTRING(@alpha, (m DIV 56800235584) MOD 62 + 1, 1),
                       SUBSTRING(@alpha, (m DIV 916132832) MOD 62 + 1, 1),
                       SUBSTRING(@alpha, (m DIV 14776336) MOD 62 + 1, 1),
                       SUBSTRING(@alpha, (m DIV 238328) MOD 62 + 1, 1),
                       SUBSTRING(@alpha, (m DIV 3844) MOD 62 + 1, 1),
                       SUBSTRING(@alpha, (m DIV 62) MOD 62 + 1, 1),
                       SUBSTRING(@alpha, m MOD 62 + 1, 1)
                   ),
               CONCAT('https://example.com/r/', m, '/', REPEAT('a', 150)),
               NOW() - INTERVAL (n MOD 86400) SECOND,
               NULL
        FROM (SELECT n, (n * 2654435761) MOD 3521614606208 AS m
              FROM nums
              WHERE n < @target) t;

        DROP TABLE nums;
        ANALYZE TABLE link;

        SELECT CONCAT('link rows = ', COUNT(*)) AS msg FROM link;
    END IF;
END $$

DELIMITER ;

CALL bench_load(10000000);
DROP PROCEDURE bench_load;
