import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;

public class Registro {
    private final Writer escritor;
    private final boolean sincronizado;

    public Registro(String ruta, boolean sincronizado) throws IOException {
        Files.createDirectories(Path.of(ruta).getParent());
        this.escritor = new BufferedWriter(new FileWriter(ruta, true));
        this.sincronizado = sincronizado;
    }

    public void escribir(String recurso, long ms) {
        if (sincronizado) {
            // Solo un hilo a la vez arma la línea completa
            synchronized (this) {
                escribirLinea(recurso, ms);
            }
        } else {
            // Varios hilos a la vez: las líneas pueden mezclarse
            escribirLinea(recurso, ms);
        }
    }

    // La línea se escribe en varias llamadas a write(). Cada llamada es segura,
    // pero la secuencia completa NO lo es si dos hilos entran a la vez.
    private void escribirLinea(String recurso, long ms) {
        try {
            escritor.write(LocalDateTime.now().toString());
            escritor.write(" | ");
            escritor.write(recurso);
            escritor.write(" | ");
            escritor.write(ms + " ms");
            escritor.write("\n");
            escritor.flush();
        } catch (IOException e) {
            System.err.println("Error escribiendo el registro: " + e);
        }
    }

    public void cerrar() throws IOException {
        escritor.close();
    }
}
