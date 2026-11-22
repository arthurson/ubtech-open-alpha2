package android.content.res;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** 測試用 stub：冇任何資產（靜態頁一律 404）。 */
public class AssetManager {
    public InputStream open(String path) throws IOException {
        throw new FileNotFoundException(path);
    }

    public String[] list(String path) throws IOException {
        return new String[0];
    }
}
