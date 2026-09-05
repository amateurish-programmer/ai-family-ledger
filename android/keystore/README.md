# 公开开发签名

`dev-debug.jks` 仅用于本项目 Debug APK。别名 `androiddebugkey`，store/key password 均为标准测试值 `android`。它随源码公开，目的只是保持测试包覆盖安装的一致性，不代表发布者身份安全，也不能作为生产密钥使用。

正式发布时创建独立私钥，保存至受控凭据系统，绝不提交正式密钥。
