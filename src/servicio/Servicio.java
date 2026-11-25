import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

public class Servicio {
    private static Registro registro;

    public static void main(String[] args) throws IOException {
        String dirLog = System.getProperty("log.dir", "/var/log/servicio");
        registro = new Registro(dirLog + "/servicio.log", true);

        HttpServer servidor = HttpServer.create(new InetSocketAddress(8080), 0);
        servidor.createContext("/estado", Servicio::estado);
        servidor.createContext("/trabajo", Servicio::trabajo);
        // Grupo de 8 hilos: atiende hasta 8 solicitudes al mismo tiempo
        servidor.setExecutor(Executors.newFixedThreadPool(8));
        servidor.start();
        System.out.println("Servicio escuchando en el puerto 8080");
    }

    private static void estado(HttpExchange ex) throws IOException {
        long inicio = System.nanoTime();
        responder(ex, 200, "OK " + LocalDateTime.now() + "\n");
        registro.escribir("/estado", (System.nanoTime() - inicio) / 1_000_000);
    }

    private static void trabajo(HttpExchange ex) throws IOException {
        long inicio = System.nanoTime();
        long n = 100000;
        String consulta = ex.getRequestURI().getQuery();
        try {
            if (consulta != null && consulta.startsWith("n=")) {
                n = Long.parseLong(consulta.substring(2));
            }
        } catch (NumberFormatException e) {
            responder(ex, 400, "n debe ser un numero\n");
            return;
        }
        if (n < 2 || n > 3_000_000) {
            responder(ex, 400, "n debe estar entre 2 y 3000000\n");
            return;
        }
        long primos = contarPrimos(n);
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        responder(ex, 200, "primos hasta " + n + " = " + primos + " (" + ms + " ms)\n");
        registro.escribir("/trabajo", ms);
    }

    // Trabajo que consume CPU: cuenta primos probando divisores
    private static long contarPrimos(long n) {
        long cuenta = 0;
        for (long i = 2; i <= n; i++) {
            boolean primo = true;
            for (long d = 2; d * d <= i; d++) {
                if (i % d == 0) { primo = false; break; }
            }
            if (primo) cuenta++;
        }
        return cuenta;
    }

    private static void responder(HttpExchange ex, int codigo, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
