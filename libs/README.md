# libs/

构建需要 **MTR 发布产物** 作为编译期依赖，但本仓库不包含、也不再分发它的代码。

放置方式（任选其一）：

```bash
# A. 从 MTR 发布 jar 中提取 org/mtr 后重新打包
jar xf minecraft-transit-railway-4.0.5.jar org
jar cf libs/mtr-compile.jar org
rm -rf org

# B. 直接使用你本地已有的编译用 jar
copy <path-to-mtr-compile.jar> libs/mtr-compile.jar
```

文件名固定为 `libs/mtr-compile.jar`，已被 `.gitignore` 忽略。
