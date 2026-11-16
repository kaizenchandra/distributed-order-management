import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

public class TlsProbe {
    public static void main(String[] args) throws Exception {
        var props = new Properties();
        try (var in = Files.newInputStream(Path.of(args[2]))) {
            props.load(in);
        }
        var trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(props.getProperty("ssl.truststore.location")))) {
            trust.load(in, props.getProperty("ssl.truststore.password").toCharArray());
        }
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm.getTrustManagers(), null);
        try (var socket = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            socket.connect(new java.net.InetSocketAddress(args[0], Integer.parseInt(args[1])), 3000);
            socket.setSoTimeout(3000);
            var config = socket.getSSLParameters();
            config.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(config);
            socket.startHandshake();
            var cert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            System.out.println(
                    args[0]
                            + " "
                            + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        }
    }
}
