# 磁盘使用率排查

当根分区使用率超过 85% 时，先确认增长来自日志、缓存还是业务数据。

1. 执行 df -h 查看挂载点
2. 执行 du -sh /\* 定位大目录

<table><tr><td><p>挂载点</p></td><td><p>阈值</p></td><td><p>动作</p></td></tr><tr><td><p>/var</p></td><td><p>85%</p></td><td><p>清理日志</p></td></tr><tr><td><p>/data</p></td><td><p>90%</p></td><td><p>扩容磁盘</p></td></tr></table>