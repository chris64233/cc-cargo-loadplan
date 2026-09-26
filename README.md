# cc-cargo-loadplan

航班货舱与货物单元管理服务。

当前包含可启动的服务入口、持久化依赖和应用上下文测试。

## 开发环境

- JDK 21
- Maven Wrapper 3.9.9
- Spring Boot 4.1.1

迁移项目沿用现有 Spring Boot 版本，其他项目使用上述版本。

## 常用命令

运行测试：

    ./mvnw clean test

启动服务：

    ./mvnw spring-boot:run
