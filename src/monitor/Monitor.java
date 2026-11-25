import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Monitor.java - Monitor de recursos parecido a "top".
 * Lee DIRECTAMENTE los archivos de /proc y /sys/fs/cgroup.
 * No ejecuta ps, top, free ni ningun otro comando.
 *
 * Uso: java Monitor [segundos] [archivo.csv] [filas]
 *   segundos     cada cuanto se actualiza (por defecto 2)
 *   archivo.csv  si se indica, guarda una fila por cada actualizacion
 *   filas        cuantos procesos mostrar (por defecto 15)
 */
public class Monitor {

    // ---------- Constantes ----------
    // Ticks de CPU por segundo. En Linux es 100 (es lo que devuelve "getconf CLK_TCK").
    static final long CLK_TCK = 100;
    // Carpeta del cgroup del servicio (la crea systemd al arrancar servicio.service)
    static final String CGROUP = "/sys/fs/cgroup/system.slice/servicio.service";
    static final double UMBRAL_MEM = 0.80;    // alerta si el servicio usa >= 80 % de su limite
    static final long UMBRAL_SWAP_KB = 1024;  // alerta si el swap usado supera 1 MB
    static final String ROJO = "\033[1;31m", VERDE = "\033[32m", FIN = "\033[0m";
    static final String CABECERA = "hora,mem_total_mb,mem_disp_mb,swap_total_mb,swap_usado_mb,"
            + "cpu_total_pct,steal_pct,fallos_mayores_total,fallos_mayores_por_s,"
            + "svc_mem_mb,svc_mem_limite_mb,svc_cpu_pct,svc_oom_kill,proc_top,proc_top_cpu_pct";

    // Datos de un proceso en un instante, y una fila de la tabla (proceso + su %CPU)
    record Proc(int pid, String nombre, char estado, int hilos, long virtKb, long rssKb,
                long minflt, long majflt, long ticks) {}
    record Fila(Proc p, double cpu) {}

    // ---------- Programa principal ----------
    public static void main(String[] args) throws Exception {
        int intervalo = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        Path csv = args.length > 1 ? Path.of(args[1]) : null;
        int filas = args.length > 2 ? Integer.parseInt(args[2]) : 15;

        // Primera muestra: sirve de punto de partida para calcular diferencias
        Map<Integer, Proc> procAnt = leerProcesos();
        long[] cpuAnt = leerCpuTotal();
        long usoSvcAnt = claveCgroup("cpu.stat", "usage_usec");
        long majAnt = leerVmstat("pgmajfault");
        long tAnt = System.nanoTime();
        System.out.println("Tomando la primera muestra, espera " + intervalo + " s...");

        while (true) {
            Thread.sleep(intervalo * 1000L);
            long t = System.nanoTime();
            double dt = (t - tAnt) / 1e9;   // segundos reales entre las dos muestras

            // --- Procesos y su % de CPU ---
            Map<Integer, Proc> procAct = leerProcesos();
            List<Fila> tabla = new ArrayList<>();
            for (Proc p : procAct.values()) {
                Proc a = procAnt.get(p.pid());
                // Si el proceso es nuevo (no estaba en la muestra anterior) su CPU es 0
                double cpu = (a == null) ? 0.0
                        : (p.ticks() - a.ticks()) / (double) CLK_TCK / dt * 100.0;
                tabla.add(new Fila(p, cpu));
            }
            // Orden: mayor %CPU primero; si empatan, el que usa mas RAM
            tabla.sort((x, y) -> {
                int c = Double.compare(y.cpu(), x.cpu());
                return c != 0 ? c : Long.compare(y.p().rssKb(), x.p().rssKb());
            });

            // --- CPU total del sistema ---
            long[] cpuAct = leerCpuTotal();
            double dTotal = cpuAct[0] - cpuAnt[0];
            double cpuUsoPct = dTotal > 0 ? 100.0 * (dTotal - (cpuAct[1] - cpuAnt[1])) / dTotal : 0;
            double stealPct = dTotal > 0 ? 100.0 * (cpuAct[2] - cpuAnt[2]) / dTotal : 0;

            // --- Memoria del sistema ---
            Map<String, Long> mi = leerMeminfo();
            long memTotal = mi.get("MemTotal");
            long memDisp = mi.get("MemAvailable");
            long swapTotal = mi.get("SwapTotal");
            long swapUsado = swapTotal - mi.get("SwapFree");
            long majAct = leerVmstat("pgmajfault");
            double majPorS = (majAct - majAnt) / dt;

            // --- El servicio frente a sus limites (cgroup) ---
            long memSvc = numeroCgroup("memory.current");
            long memMax = numeroCgroup("memory.max");
            long memHigh = numeroCgroup("memory.high");
            long memLow = numeroCgroup("memory.low");
            long oomKill = claveCgroup("memory.events", "oom_kill");
            long usoSvc = claveCgroup("cpu.stat", "usage_usec");
            long frenado = claveCgroup("cpu.stat", "nr_throttled");
            String pesoCpu = nd(leerArchivo(CGROUP + "/cpu.weight"));
            String maxCpu = nd(leerArchivo(CGROUP + "/cpu.max"));
            boolean haySvc = memSvc >= 0;
            // usage_usec son microsegundos de CPU acumulados: se divide entre los microsegundos reales
            double cpuSvcPct = (usoSvc >= 0 && usoSvcAnt >= 0)
                    ? (usoSvc - usoSvcAnt) / (dt * 1e6) * 100.0 : 0;
            // El limite que importa es el menor entre memory.high y memory.max (si estan definidos)
            long limite = -1;
            for (long v : new long[]{memHigh, memMax}) {
                if (v > 0 && (limite < 0 || v < limite)) limite = v;
            }

            // --- Alertas ---
            List<String> alertas = new ArrayList<>();
            if (haySvc && limite > 0 && memSvc >= UMBRAL_MEM * limite)
                alertas.add(f("El servicio usa %.0f %% de su limite de memoria", 100.0 * memSvc / limite));
            if (swapUsado > UMBRAL_SWAP_KB)
                alertas.add(f("El sistema esta usando swap: %.1f MB", swapUsado / 1024.0));
            if (oomKill > 0)
                alertas.add("El servicio fue detenido por falta de memoria (oom_kill=" + oomKill + ")");

            // --- Dibujar la pantalla (se arma todo en un texto y se imprime de una vez) ---
            String hora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            StringBuilder s = new StringBuilder();
            s.append("\033[H\033[2J");   // codigo ANSI: borra la pantalla y sube el cursor
            s.append(f("=== MONITOR  %s  (cada %d s, Ctrl+C para salir) ===%n", hora, intervalo));
            s.append(f("MEMORIA  total %d MB | disponible %d MB | swap total %d MB, usado %.1f MB%n",
                    memTotal / 1024, memDisp / 1024, swapTotal / 1024, swapUsado / 1024.0));
            s.append(f("CPU      uso total %.1f %% | steal %.1f %%  (sobre todas las CPU)%n", cpuUsoPct, stealPct));
            s.append(f("FALLOS DE PAGINA MAYORES (sistema): %d en total, %.1f por segundo%n", majAct, majPorS));

            s.append("\nSERVICIO (cgroup system.slice/servicio.service)\n");
            if (!haySvc) {
                s.append(ROJO + "  No se encontro el cgroup del servicio. Esta corriendo 'servicio'?" + FIN + "\n");
            } else {
                double frac = limite > 0 ? (double) memSvc / limite : 0;
                s.append(f("  Memoria: %s (limite: %s) %s %.0f %%%n",
                        mbTxt(memSvc), mbTxt(limite), barra(frac, 20), frac * 100));
                s.append(f("  low=%s  high=%s  max=%s%n", mbTxt(memLow), mbTxt(memHigh), mbTxt(memMax)));
                s.append(f("  CPU: %.1f %% de una CPU | cpu.weight=%s | cpu.max=%s | veces frenado=%d%n",
                        cpuSvcPct, pesoCpu, maxCpu, frenado));
                s.append(f("  OOM kills del servicio: %d%n", oomKill));
            }

            s.append("\nALERTAS: ");
            if (alertas.isEmpty()) {
                s.append(VERDE + "ninguna" + FIN + "\n");
            } else {
                s.append("\n");
                for (String a : alertas) s.append(ROJO + "  [!] " + a + FIN + "\n");
            }

            s.append(f("%n%7s %-16s %s %5s %9s %8s %9s %7s %6s%n",
                    "PID", "NOMBRE", "E", "HILOS", "VIRT(MB)", "RSS(MB)", "MINFLT", "MAJFLT", "%CPU"));
            for (int i = 0; i < Math.min(filas, tabla.size()); i++) {
                Proc p = tabla.get(i).p();
                String nom = p.nombre().length() > 16 ? p.nombre().substring(0, 16) : p.nombre();
                s.append(f("%7d %-16s %c %5d %9.1f %8.1f %9d %7d %6.1f%n", p.pid(), nom, p.estado(),
                        p.hilos(), p.virtKb() / 1024.0, p.rssKb() / 1024.0, p.minflt(), p.majflt(),
                        tabla.get(i).cpu()));
            }
            System.out.print(s);
            System.out.flush();

            // --- Exportar una fila al CSV ---
            if (csv != null) {
                Proc top = tabla.get(0).p();
                String fila = f("%s,%d,%d,%d,%.1f,%.1f,%.1f,%d,%.1f,%s,%s,%.1f,%d,%s,%.1f",
                        hora, memTotal / 1024, memDisp / 1024, swapTotal / 1024, swapUsado / 1024.0,
                        cpuUsoPct, stealPct, majAct, majPorS,
                        haySvc ? f("%.1f", memSvc / 1048576.0) : "",
                        limite > 0 ? f("%.1f", limite / 1048576.0) : "",
                        cpuSvcPct, Math.max(oomKill, 0), top.nombre(), tabla.get(0).cpu());
                escribirCsv(csv, fila);
            }

            // La muestra de ahora pasa a ser la "anterior" de la proxima vuelta
            procAnt = procAct;
            cpuAnt = cpuAct;
            usoSvcAnt = usoSvc;
            majAnt = majAct;
            tAnt = t;
        }
    }

    // ---------- Lectura de /proc ----------

    // Recorre /proc: cada carpeta con nombre numerico es un proceso (su PID)
    static Map<Integer, Proc> leerProcesos() {
        Map<Integer, Proc> mapa = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/proc"))) {
            for (Path p : ds) {
                String nombre = p.getFileName().toString();
                if (!nombre.chars().allMatch(Character::isDigit)) continue;
                try {
                    Proc proc = leerProceso(Integer.parseInt(nombre));
                    mapa.put(proc.pid(), proc);
                } catch (IOException | RuntimeException e) {
                    // El proceso termino mientras lo leiamos: se ignora
                }
            }
        } catch (IOException e) {
            System.err.println("No se pudo leer /proc: " + e);
        }
        return mapa;
    }

    static Proc leerProceso(int pid) throws IOException {
        // --- /proc/<pid>/stat: fallos de pagina y tiempo de CPU ---
        String s = Files.readString(Path.of("/proc/" + pid + "/stat"));
        // El nombre va entre parentesis y puede tener espacios: se corta despues del ULTIMO ')'
        String[] f = s.substring(s.lastIndexOf(')') + 2).trim().split(" ");
        // El campo numero N de la documentacion queda en f[N - 3]
        long minflt = Long.parseLong(f[10 - 3]);                          // fallos menores
        long majflt = Long.parseLong(f[12 - 3]);                          // fallos mayores
        long ticks = Long.parseLong(f[14 - 3]) + Long.parseLong(f[15 - 3]); // utime + stime

        // --- /proc/<pid>/status: nombre, estado, hilos y memoria ---
        String nombre = "?";
        char estado = '?';
        int hilos = 0;
        long virt = 0, rss = 0;   // los procesos del kernel no tienen VmSize/VmRSS: quedan en 0
        for (String linea : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
            if (linea.startsWith("Name:")) nombre = valor(linea);
            else if (linea.startsWith("State:")) estado = valor(linea).charAt(0);
            else if (linea.startsWith("Threads:")) hilos = Integer.parseInt(valor(linea));
            else if (linea.startsWith("VmSize:")) virt = Long.parseLong(valor(linea).split("\\s+")[0]);
            else if (linea.startsWith("VmRSS:")) rss = Long.parseLong(valor(linea).split("\\s+")[0]);
        }
        return new Proc(pid, nombre, estado, hilos, virt, rss, minflt, majflt, ticks);
    }

    // De "VmRSS:     1234 kB" devuelve "1234 kB"
    static String valor(String linea) {
        return linea.substring(linea.indexOf(':') + 1).trim();
    }

    // /proc/meminfo: lineas como "MemTotal:   1011496 kB" -> mapa nombre -> valor en kB
    static Map<String, Long> leerMeminfo() throws IOException {
        Map<String, Long> m = new HashMap<>();
        for (String linea : Files.readAllLines(Path.of("/proc/meminfo"))) {
            String[] p = linea.split(":");
            m.put(p[0].trim(), Long.parseLong(p[1].trim().split("\\s+")[0]));
        }
        return m;
    }

    // Primera linea de /proc/stat: "cpu user nice system idle iowait irq softirq steal ..."
    // Devuelve {tiempo total, tiempo ocioso, tiempo steal} en ticks
    static long[] leerCpuTotal() throws IOException {
        String[] p = Files.readAllLines(Path.of("/proc/stat")).get(0).trim().split("\\s+");
        long total = 0;
        for (int i = 1; i <= 8; i++) total += Long.parseLong(p[i]);
        long ocioso = Long.parseLong(p[4]) + Long.parseLong(p[5]);   // idle + iowait
        long steal = Long.parseLong(p[8]);
        return new long[]{total, ocioso, steal};
    }

    // /proc/vmstat: lineas "clave valor". pgmajfault = fallos de pagina mayores de todo el sistema
    static long leerVmstat(String clave) {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/vmstat"))) {
                if (l.startsWith(clave + " ")) return Long.parseLong(l.split(" ")[1]);
            }
        } catch (IOException e) {
            // se devuelve 0
        }
        return 0;
    }

    // ---------- Lectura de /sys/fs/cgroup ----------

    // Contenido de un archivo, o null si no existe
    static String leerArchivo(String ruta) {
        try {
            return Files.readString(Path.of(ruta)).trim();
        } catch (IOException e) {
            return null;
        }
    }

    // Archivos con un solo numero. "max" (sin limite) -> -1. Si falta el archivo -> -2
    static long numeroCgroup(String archivo) {
        String t = leerArchivo(CGROUP + "/" + archivo);
        if (t == null) return -2;
        if (t.equals("max")) return -1;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    // Archivos con lineas "clave valor" (cpu.stat, memory.events). Si no existe -> -2
    static long claveCgroup(String archivo, String clave) {
        String t = leerArchivo(CGROUP + "/" + archivo);
        if (t == null) return -2;
        for (String linea : t.split("\n")) {
            String[] p = linea.split(" ");
            if (p[0].equals(clave)) return Long.parseLong(p[1]);
        }
        return -2;
    }

    // ---------- Utilidades de texto ----------

    static String f(String formato, Object... args) {
        return String.format(Locale.US, formato, args);   // Locale.US: punto decimal, no coma
    }

    static String nd(String x) {
        return x == null ? "n/d" : x;
    }

    static String mbTxt(long bytes) {
        if (bytes == -2) return "n/d";
        if (bytes == -1) return "sin limite";
        return f("%.0f MB", bytes / 1048576.0);
    }

    static String barra(double fraccion, int ancho) {
        int llenos = (int) Math.round(Math.min(Math.max(fraccion, 0), 1) * ancho);
        return "[" + "#".repeat(llenos) + "-".repeat(ancho - llenos) + "]";
    }

    static void escribirCsv(Path csv, String fila) throws IOException {
        if (!Files.exists(csv) || Files.size(csv) == 0) {
            Files.writeString(csv, CABECERA + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(csv, fila + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
