import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

/**
 * Servicio.java - Servicio HTTP de la empresa (puerto 8080).
 * Ofrece dos rutas:
 *   /estado          responde de inmediato "OK" y la hora (es la que se vigila: debe ser rapida).
 *   /trabajo?n=N     cuenta los primos hasta N (consume CPU a proposito, simula trabajo real).
 * Cada solicitud deja una linea en el registro (Registro.java).
 */
public class Servicio {
    // Un solo objeto Registro compartido por todos los hilos del servidor
    private static Registro registro;

    public static void main(String[] args) throws IOException {
        // El directorio del registro se recibe con -Dlog.dir=... (lo pone la unidad de systemd).
        // Si no se indica, se usa /var/log/servicio.
        String dirLog = System.getProperty("log.dir", "/var/log/servicio");
        // true = escritura sincronizada: un solo hilo arma cada linea completa (evita lineas danadas)
        registro = new Registro(dirLog + "/servicio.log", true);

        // Servidor HTTP del propio JDK. El segundo argumento (0) usa la cola por defecto.
        HttpServer servidor = HttpServer.create(new InetSocketAddress(8080), 0);
        // Cada ruta se asocia a un metodo que la atiende
        servidor.createContext("/estado", Servicio::estado);
        servidor.createContext("/trabajo", Servicio::trabajo);
        // Grupo fijo de 8 hilos: atiende hasta 8 solicitudes al mismo tiempo.
        // Con 2 CPU, mas hilos solo aumentarian los cambios de contexto y la memoria de pilas;
        // con menos, un /trabajo largo podria dejar esperando a /estado.
        servidor.setExecutor(Executors.newFixedThreadPool(8));
        servidor.start();
        System.out.println("Servicio escuchando en el puerto 8080");
    }

    // Atiende /estado: responde rapido y guarda cuanto tardo
    private static void estado(HttpExchange ex) throws IOException {
        long inicio = System.nanoTime();   // reloj de alta precision para medir la duracion
        responder(ex, 200, "OK " + LocalDateTime.now() + "\n");
        registro.escribir("/estado", (System.nanoTime() - inicio) / 1_000_000);   // nanosegundos -> ms
    }

    // Atiende /trabajo?n=N: valida N, cuenta primos y responde con el resultado y el tiempo
    private static void trabajo(HttpExchange ex) throws IOException {
        long inicio = System.nanoTime();
        long n = 100000;   // valor por defecto si no se indica n
        String consulta = ex.getRequestURI().getQuery();   // texto despues del "?", por ejemplo "n=500000"
        try {
            if (consulta != null && consulta.startsWith("n=")) {
                n = Long.parseLong(consulta.substring(2));
            }
        } catch (NumberFormatException e) {
            // n no era un numero: error 400 (solicitud incorrecta)
            responder(ex, 400, "n debe ser un numero\n");
            return;
        }
        // Se limita n para que nadie pueda pedir un calculo que dure demasiado
        if (n < 2 || n > 3_000_000) {
            responder(ex, 400, "n debe estar entre 2 y 3000000\n");
            return;
        }
        long primos = contarPrimos(n);
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        responder(ex, 200, "primos hasta " + n + " = " + primos + " (" + ms + " ms)\n");
        registro.escribir("/trabajo", ms);
    }

    // Trabajo que consume CPU: cuenta primos probando divisores.
    // Es deliberadamente simple: lo importante es que gaste CPU de forma medible.
    private static long contarPrimos(long n) {
        long cuenta = 0;
        for (long i = 2; i <= n; i++) {
            boolean primo = true;
            // Basta probar divisores hasta la raiz cuadrada de i (d * d <= i)
            for (long d = 2; d * d <= i; d++) {
                if (i % d == 0) { primo = false; break; }
            }
            if (primo) cuenta++;
        }
        return cuenta;
    }

    // Envia la respuesta HTTP: primero las cabeceras (codigo y largo) y luego el cuerpo
    private static void responder(HttpExchange ex, int codigo, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(codigo, bytes.length);
        // try-with-resources: cierra el flujo de salida aunque ocurra un error
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
