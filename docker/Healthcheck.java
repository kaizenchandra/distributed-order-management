import java.net.*;
import java.net.http.*;
import java.time.*;

class Healthcheck {
    public static void main(String[] args) throws Exception {
        var c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var r =
                c.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:8080/actuator/health/readiness"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        System.exit(r.statusCode() == 200 ? 0 : 1);
    }
}
