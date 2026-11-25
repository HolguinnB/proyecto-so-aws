import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Descontrolado.java - Proceso "descontrolado" para probar las garantias.
 * Intenta acaparar la CPU y la memoria. Se detiene solo al cumplirse el tiempo.
 *
 * Uso: java -Xmx<N>m -cp <carpeta> Descontrolado [segundos] [maxMB] [hilosCPU]
 *   segundos  cuanto tiempo corre (por defecto 150)
 *   maxMB     cuanta memoria intenta reservar; 0 = no toca la memoria (por defecto 1200)
 *   hilosCPU  hilos que queman CPU; 0 = no toca la CPU (por defecto: el doble de CPUs)
 * Ojo: para reservar mucha memoria hay que subir el limite de Java con -Xmx.
 */
public class Descontrolado {
    static volatile boolean activo = true;
    // El resultado se guarda aqui para que Java no elimine el calculo por "inutil"
    static volatile double sumidero;

    public static void main(String[] args) throws Exception {
        int segundos = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        int maxMb = args.length > 1 ? Integer.parseInt(args[1]) : 1200;
        int hilosCpu = args.length > 2 ? Integer.parseInt(args[2])
                : Runtime.getRuntime().availableProcessors() * 2;
        long fin = System.currentTimeMillis() + segundos * 1000L;
        System.out.println("Descontrolado: " + hilosCpu + " hilos de CPU, hasta " + maxMb
                + " MB de memoria, durante " + segundos + " s");

        // --- CPU: cada hilo hace calculos sin parar ---
        for (int i = 0; i < hilosCpu; i++) {
            Thread t = new Thread(() -> {
                double x = 0;
                while (activo) {
                    for (int j = 1; j < 1_000_000; j++) x += Math.sqrt(j) * Math.sin(j);
                    sumidero = x;
                }
            });
            t.setDaemon(true);   // no impide que el programa termine
            t.start();
        }

        // --- Memoria: bloques de 10 MB, tocando cada pagina de 4 KB para que sea RAM real ---
        List<byte[]> bloques = new ArrayList<>();
        Random azar = new Random();
        int mb = 0;
        long proximaLinea = System.currentTimeMillis();
        while (System.currentTimeMillis() < fin) {
            if (mb < maxMb) {
                try {
                    byte[] b = new byte[10 * 1024 * 1024];
                    for (int i = 0; i < b.length; i += 4096) b[i] = 1;
                    bloques.add(b);
                    mb += 10;
                } catch (OutOfMemoryError e) {
                    System.out.println("Java no pudo reservar mas memoria (limite -Xmx): " + mb + " MB");
                    maxMb = mb;
                }
            } else if (!bloques.isEmpty()) {
                // Ya llego al tope: sigue recorriendo bloques al azar. Si el sistema los mando
                // al swap, cada acceso provoca un fallo de pagina mayor.
                byte[] b = bloques.get(azar.nextInt(bloques.size()));
                for (int i = 0; i < b.length; i += 4096) b[i]++;
            }
            if (System.currentTimeMillis() >= proximaLinea) {
                long restante = (fin - System.currentTimeMillis()) / 1000;
                System.out.println("memoria reservada: " + mb + " MB | quedan " + restante + " s");
                proximaLinea += 1000;
            }
            Thread.sleep(50);
        }
        activo = false;
        System.out.println("Tiempo cumplido. Fin.");
    }
}
