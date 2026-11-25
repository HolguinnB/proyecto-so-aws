import java.nio.file.*;
import java.util.regex.Pattern;

public class PruebaRegistro {
    public static void main(String[] args) throws Exception {
        boolean sync = args.length > 0 && args[0].equals("con");
        String ruta = "/tmp/prueba-" + (sync ? "con" : "sin") + "-sync.log";
        Files.deleteIfExists(Path.of(ruta));
        Registro reg = new Registro(ruta, sync);

        int hilos = 20, porHilo = 2000;
        Thread[] ts = new Thread[hilos];
        for (int i = 0; i < hilos; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < porHilo; j++) reg.escribir("/prueba", j);
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        reg.cerrar();

        // Una línea sana tiene exactamente el formato: fecha | recurso | numero ms
        Pattern sana = Pattern.compile("^\\S+ \\| \\S+ \\| \\d+ ms$");
        long total = 0, daniadas = 0;
        for (String linea : Files.readAllLines(Path.of(ruta))) {
            total++;
            if (!sana.matcher(linea).matches()) daniadas++;
        }
        System.out.println((sync ? "CON" : "SIN") + " sincronizar: "
                + daniadas + " lineas danadas de " + total);
    }
}
