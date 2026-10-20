package com.zz.pgadskip;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** 只覆盖上次由 app 写出且未经修改的名单，避免升级丢掉用户规则。 */
final class BuiltinLists {
    static synchronized void ensure(Context c, String name, int raw, int hashRes) throws Exception {
        File file = file(c, name);
        String bundled = c.getString(hashRes);
        String key = "builtin_hash_" + name;
        String previous = Prefs.sp(c).getString(key, "");
        if (!file.exists()) {
            write(c, file, raw, bundled, key);
            return;
        }
        String current;
        try (InputStream in = new FileInputStream(file)) { current = hash(read(in)); }
        if (current.equals(bundled)) {
            // 旧版没有哈希；内容恰好一致时也可以安全建立基线。
            if (!bundled.equals(previous)) remember(c, key, bundled);
        } else if (!previous.isEmpty() && current.equals(previous)) {
            write(c, file, raw, bundled, key);
        }
        // 无基线的旧文件和用户修改过的文件都保留，不猜它们的来源。
    }

    static synchronized void restore(Context c) throws Exception {
        write(c, file(c, "blocklist.txt"), R.raw.blocklist, c.getString(R.string.blocklist_hash), "builtin_hash_blocklist.txt");
        write(c, file(c, "hide.txt"), R.raw.hide, c.getString(R.string.hide_hash), "builtin_hash_hide.txt");
    }

    static File file(Context c, String name) throws IOException {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new IOException("本机名单目录不可用");
        return new File(dir, name);
    }

    static void write(Context c, File file, int raw, String expected, String key) throws Exception {
        byte[] bytes;
        try (InputStream in = c.getResources().openRawResource(raw)) { bytes = read(in); }
        if (!hash(bytes).equals(expected)) throw new IOException("内置名单校验失败");
        AtomicFile target = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = target.startWrite();
            out.write(bytes);
            target.finishWrite(out);
        } catch (IOException e) {
            target.failWrite(out);
            throw e;
        }
        try (InputStream in = target.openRead()) {
            if (!hash(read(in)).equals(expected)) throw new IOException("名单写入校验失败");
        }
        // 文件写成功后才记哈希，写入失败不会让旧文件失去保护。
        remember(c, key, expected);
    }

    static void remember(Context c, String key, String hash) throws IOException {
        if (!Prefs.sp(c).edit().putString(key, hash).commit()) throw new IOException("名单版本保存失败");
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : digest) s.append(Character.forDigit((b & 0xFF) >> 4, 16)).append(Character.forDigit(b & 15, 16));
        return s.toString();
    }
}
