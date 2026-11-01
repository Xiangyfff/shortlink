# A/B 实验用的 compose 覆盖文件

这些不是日常使用的配置，是做 [§19 改前/改后归因](../../docs/decisions.md) 时的一次性实验环境。
放在这里是为了不污染根目录——陌生人 clone 下来只需要看根目录那两个。

用法：和主文件叠加，**注意 `-f` 的顺序**（后面的覆盖前面的）。

```bash
# A 组：统计开（正常配置）
docker compose -f ../../docker-compose.yml -f ../../docker-compose.limit.yml up -d

# B 组：统计全关（纯读路径基线）
docker compose -f ../../docker-compose.yml -f ../../docker-compose.limit.yml \
  -f docker-compose.ab-off.yml up -d

# D 组：生产者开、消费者关（把发送端和消费端的 CPU 成本切开）
docker compose -f ../../docker-compose.yml -f ../../docker-compose.limit.yml \
  -f docker-compose.ab-noconsumer.yml up -d

# E 组：生产者启动但绝不入队（测"客户端存在"本身的开销）
docker compose -f ../../docker-compose.yml -f ../../docker-compose.limit.yml \
  -f docker-compose.ab-e.yml up -d

# JFR 采样（跑完 docker stop，录制文件落在 /app/data）
docker compose -f ../../docker-compose.yml -f ../../docker-compose.limit.yml \
  -f docker-compose.jfr.yml up -d
```

四组结果见 [`docs/decisions.md` §19](../../docs/decisions.md)。

> 这些文件里改的是 `SPRING_APPLICATION_JSON`，会覆盖根目录 `.env` 和 `limit.yml` 的同名配置。
> 实验完记得用不带 `-f` 的命令切回正常配置，否则统计一直是关的。
