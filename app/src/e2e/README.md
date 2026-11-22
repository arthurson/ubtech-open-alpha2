# app/src/e2e — JVM 整合測試（唔使 Android）

呢個目錄**唔係** Gradle sourceSet（`gradlew test` 唔會編譯佢），只由 `scripts/test-apivalidator.py`
（CI 跑）用純 `javac`／`java` 編譯執行。

| 目錄 | 內容 |
|---|---|
| `stubs/` | 最小 Android stub（`Log`、`Base64`、`AssetManager`、`Context`、`SharedPreferences`、`MainActivity.PREFS_NAME`）。**只可以喺呢個測試用**：編譯時 `-sourcepath stubs:app/src/main/java`，stub 排前面，所以 `MainActivity` 係 stub 版本。唔好將佢哋放入正式 source set。 |
| `java/` | `HttpServerE2ETest`（真 socket 對 `HttpServer`）、`HttpFloodE2ETest`（`-Xmx64m` 洪水：Content-Length／WebSocket frame 預先配置記憶體）、`PanelAuthE2ETest`（`PanelAuth` 預設啟用、token 節流，全程假 SharedPreferences＋假時鐘）。 |

點解唔用真 `android.jar`：佢嘅方法 body 係 `throw new RuntimeException("Stub!")`，運行時一 call 就爆。

測試用固定 port `HttpServer.PORT`（8888），逐個 JVM 順序執行；本機如果同時開住真嘅 app／其他程式佔用 8888，測試會明確報 bind 失敗。
設定環境變數 `E2E_LOG=1` 可以睇 stub `Log` 輸出（排查用）。
