# Redis ZSet 的适用场景

**ZSet（Sorted Set，有序集合）** 为每个成员关联一个 score，并按 score 自动排序，适合排行榜、延时队列、按时间点分段查询等场景。常用命令包括 ZADD、ZRANGEBYSCORE、ZREVRANGE。

## Sources
- [Redis Sorted Sets](https://redis.io/docs/latest/develop/data-types/sorted-sets/)
