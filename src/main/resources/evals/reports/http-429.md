# HTTP 429 Too Many Requests

状态码 **429 Too Many Requests** 表示客户端在给定时间内发送了过多请求，服务器已触发限流（rate limiting）。响应通常附带 Retry-After 头，用于告知建议的重试等待时间。

## Sources
- [RFC 6585](https://datatracker.ietf.org/doc/html/rfc6585)
