#!/bin/bash
# experimento1.sh - Experimento 1 (CPU): 3 repeticiones SIN y CON garantías, alternando.
# Cada corrida: monitor + 6 clientes de /trabajo + descontrolado (4 hilos de CPU) + prueba de /estado.
# Uso: ./experimento1.sh [carpeta_salida]
DIR="${1:-$HOME/exp1}"
mkdir -p "$DIR"
cd "$(dirname "$(readlink -f "$0")")"
grep -q tiempo_s carga_servicio.sh || { echo "carga_servicio.sh es la version vieja: haz git pull"; exit 1; }

# --runtime: el cambio dura hasta el reinicio y no toca los archivos de /etc
aplicar() {
    if [ "$1" = sin ]; then
        sudo systemctl set-property --runtime user.slice CPUWeight=100 CPUQuota= MemoryHigh=infinity MemoryMax=infinity
        sudo systemctl set-property --runtime system.slice MemoryLow=0
        sudo systemctl set-property --runtime servicio.service MemoryLow=0 MemoryHigh=infinity MemoryMax=infinity
    else
        sudo systemctl set-property --runtime user.slice CPUWeight=20 CPUQuota=100% MemoryHigh=450M MemoryMax=550M
        sudo systemctl set-property --runtime system.slice MemoryLow=150M
        sudo systemctl set-property --runtime servicio.service MemoryLow=150M MemoryHigh=250M MemoryMax=300M
    fi
}

echo "modo,rep,estado_bajo_1s_pct,estado_prom_s,estado_max_s,trabajos_completados,trabajo_prom_s,cpu_servicio_prom_pct,steal_prom_pct" > "$DIR/resumen.csv"

for r in 1 2 3; do
  for modo in sin con; do
    echo "=== Repeticion $r: $modo garantias ($(date +%H:%M:%S)) ==="
    aplicar $modo; sleep 3
    P="$DIR/${modo}_$r"; rm -f "$P".*
    java -cp /opt/herramientas Monitor 2 "$P.monitor.csv" 5 >/dev/null 2>&1 &
    MON=$!
    ./carga_servicio.sh 6 130 1000000 "$P.trabajo.csv" > "$P.carga.txt" &
    java -cp /opt/herramientas Descontrolado 135 0 >/dev/null 2>&1 &
    sleep 2
    ./prueba_garantias.sh "$P.estado.csv" | tee "$P.prueba.txt"
    sleep 15; kill $MON; wait 2>/dev/null

    E=$(tail -n +2 "$P.estado.csv" | LC_ALL=C awk -F, '{n++; if($2<1)b++; s+=$2; if($2>m)m=$2} END{printf "%.1f,%.3f,%.3f",100*b/n,s/n,m}')
    T=$(tail -n +2 "$P.trabajo.csv" | LC_ALL=C awk -F, '{n++; s+=$2} END{if(n) printf "%d,%.2f",n,s/n; else printf "0,0"}')
    C=$(tail -n +2 "$P.monitor.csv" | LC_ALL=C awk -F, '$12!=""{s+=$12;n++} END{if(n) printf "%.1f",s/n; else printf "0"}')
    S=$(tail -n +2 "$P.monitor.csv" | LC_ALL=C awk -F, '{s+=$7;n++} END{if(n) printf "%.1f",s/n; else printf "0"}')
    echo "$modo,$r,$E,$T,$C,$S" >> "$DIR/resumen.csv"
    sleep 20
  done
done

aplicar con
echo; echo "==== RESUMEN ===="; column -s, -t "$DIR/resumen.csv"
