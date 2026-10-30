package kz.home.tvlauncher;

import android.app.Application;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 6 и старше не знает корневой сертификат Let's Encrypt (ISRG Root X1),
 * поэтому многие https-трансляции на таком ТВ не открываются. Добавляем этот корень
 * к системным: сначала проверка обычная, и только если она не прошла — по ISRG.
 */
public class TvApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            installExtraRoots();
        } catch (Exception ignored) {
            // Если не получилось, работаем с обычными системными сертификатами.
        }
    }

    private void installExtraRoots() throws Exception {
        KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
        extra.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int[] ids = {R.raw.isrg_root_x1, R.raw.isrg_root_x2};
        for (int i = 0; i < ids.length; i++) {
            InputStream in = getResources().openRawResource(ids[i]);
            try {
                extra.setCertificateEntry("isrg" + i, cf.generateCertificate(in));
            } finally {
                in.close();
            }
        }

        final X509TrustManager system = trustManager(null);
        final X509TrustManager bundled = trustManager(extra);

        X509TrustManager combined = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    bundled.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> all = new ArrayList<>(Arrays.asList(system.getAcceptedIssuers()));
                all.addAll(Arrays.asList(bundled.getAcceptedIssuers()));
                return all.toArray(new X509Certificate[0]);
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{combined}, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
    }

    private static X509TrustManager trustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager");
    }
}
