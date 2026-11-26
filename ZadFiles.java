package com.zadalmuslim.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;

/**
 * تخزين دائم للتنزيلات (الصوتيات والتفاسير والأذانات) في مجلد ظاهر بذاكرة الهاتف:
 *   /storage/emulated/0/ZadAlMuslim/
 * هذا المجلد لا يُحذف عند حذف التطبيق، فإذا أعدتَ تثبيته ومنحتَه الإذن مرة أخرى
 * وجد التنزيلات كما هي ولا يحتاج لتنزيلها من جديد.
 * تُعرض للصفحة باسم window.ZadFiles.
 */
public class ZadFiles {
    private static final int REQ = 7731;
    private final Activity act;

    public ZadFiles(Activity a) {
        act = a;
    }

    private static File root() {
        return new File(Environment.getExternalStorageDirectory(), "ZadAlMuslim");
    }

    /** يحوّل مسارًا نسبيًا من الصفحة إلى ملف داخل المجلد فقط (يمنع الخروج منه بـ ..). */
    private File resolve(String rel) {
        try {
            if (rel == null || rel.length() == 0 || rel.startsWith("/") || rel.indexOf('\\') >= 0) return null;
            for (String p : rel.split("/")) {
                if (p.length() == 0 || p.equals(".") || p.equals("..")) return null;
            }
            File r = root();
            File f = new File(r, rel);
            String rc = r.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (!fc.startsWith(rc + File.separator)) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** هل مُنح الإذن؟ (أندرويد 11+: «الوصول إلى كل الملفات»، وقبلها إذن التخزين العادي). */
    @JavascriptInterface
    public boolean has() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            if (Build.VERSION.SDK_INT >= 23) {
                return act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** يفتح شاشة الإذن (أندرويد 11+) أو يطلبه مباشرة (أندرويد 10 فما دون). */
    @JavascriptInterface
    public boolean ask() {
        try {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + act.getPackageName()));
                            try {
                                act.startActivity(i);
                            } catch (Throwable t) {
                                act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        } else if (Build.VERSION.SDK_INT >= 23) {
                            act.requestPermissions(new String[]{
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ);
                        }
                    } catch (Throwable ignored) { }
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public String path() {
        return root().getAbsolutePath();
    }

    /** يكتب الملف (base64) بشكل آمن: ملف مؤقت ثم إعادة تسمية، فلا يبقى ملف ناقص إن توقف التطبيق. */
    @JavascriptInterface
    public boolean write(String rel, String b64) {
        try {
            if (!has()) return false;
            File f = resolve(rel);
            if (f == null || b64 == null) return false;
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            File dir = f.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return false;
            File nm = new File(root(), ".nomedia"); // لئلا تظهر الآيات كمقاطع في تطبيقات الموسيقى والمعرض
            if (!nm.exists()) {
                try { nm.createNewFile(); } catch (Throwable ignored) { }
            }
            File tmp = new File(dir, f.getName() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(data);
                o.getFD().sync();
            } finally {
                o.close();
            }
            if (f.exists() && !f.delete()) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** يقرأ الملف كـ base64، أو "" إن لم يوجد. */
    @JavascriptInterface
    public String read(String rel) {
        try {
            if (!has()) return "";
            File f = resolve(rel);
            if (f == null || !f.isFile()) return "";
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            } finally {
                in.close();
            }
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean remove(String rel) {
        try {
            if (!has()) return false;
            File f = resolve(rel);
            if (f == null) return false;
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /** كل الملفات داخل المجلد (مسارات نسبية، سطر لكل ملف). */
    @JavascriptInterface
    public String list() {
        StringBuilder sb = new StringBuilder();
        try {
            if (!has()) return "";
            File r = root();
            if (!r.isDirectory()) return "";
            int cut = r.getAbsolutePath().length() + 1;
            ArrayDeque<File> st = new ArrayDeque<File>();
            st.push(r);
            while (!st.isEmpty()) {
                File d = st.pop();
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (f.isDirectory()) {
                        st.push(f);
                    } else {
                        String n = f.getName();
                        if (n.equals(".nomedia") || n.endsWith(".tmp")) continue;
                        sb.append(f.getAbsolutePath().substring(cut)).append('\n');
                    }
                }
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }
}
